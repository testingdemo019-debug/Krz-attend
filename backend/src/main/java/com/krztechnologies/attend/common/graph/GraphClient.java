package com.krztechnologies.attend.common.graph;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal Microsoft Graph REST client with app-only tokens (client credentials). Timeouts on every call; 429 and 5xx
 * are retried up to 3 times honouring {@code Retry-After} (capped at 30 s). Only URLs on the configured Graph host are
 * ever called (delta links included).
 */
@Component
public class GraphClient {

    private static final Logger LOG = LoggerFactory.getLogger(GraphClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final AnonymousAuthenticationToken APP = new AnonymousAuthenticationToken(
            "krz-attend", "krz-attend", AuthorityUtils.createAuthorityList("ROLE_APP"));

    private final OAuth2AuthorizedClientManager clients;
    private final EntraProperties entra;
    private final JsonMapper json;
    private final RestClient rest;

    public GraphClient(
            OAuth2AuthorizedClientManager graphAuthorizedClientManager,
            ClientHttpRequestFactory microsoftHttpRequestFactory,
            EntraProperties entra,
            JsonMapper json) {
        this.clients = graphAuthorizedClientManager;
        this.entra = entra;
        this.json = json;
        this.rest =
                RestClient.builder().requestFactory(microsoftHttpRequestFactory).build();
    }

    /** GET a path relative to the Graph base URL (e.g. {@code /users?$top=1}) or an absolute Graph URL. */
    public JsonNode get(String pathOrUrl) {
        URI uri = resolve(pathOrUrl);
        return withRetry(() -> {
            String body = rest.get()
                    .uri(uri)
                    .headers(this::authorize)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return json.readTree(body == null ? "{}" : body);
        });
    }

    /** POST a JSON body to a path relative to the Graph base URL; Graph's response body is ignored. */
    public void post(String path, Object body) {
        URI uri = resolve(path);
        withRetry(() -> {
            rest.post()
                    .uri(uri)
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    /** Acquires an app-only token now (Settings → Microsoft sign-in → Test connection). */
    public void verifyToken() {
        token();
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(token());
    }

    private String token() {
        try {
            var request = OAuth2AuthorizeRequest.withClientRegistrationId(EntraClientRegistrations.GRAPH)
                    .principal(APP)
                    .build();
            var client = clients.authorize(request);
            if (client == null) {
                throw new GraphException(0, "no_token", "Microsoft did not issue an app token.");
            }
            return client.getAccessToken().getTokenValue();
        } catch (OAuth2AuthorizationException e) {
            throw new GraphException(401, e.getError().getErrorCode(), "Microsoft refused the app credentials.", e);
        }
    }

    private URI resolve(String pathOrUrl) {
        URI base = entra.graphBaseUrl();
        URI uri = pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://")
                ? URI.create(pathOrUrl)
                : URI.create(base.toString().replaceAll("/$", "") + pathOrUrl);
        if (!uri.getHost().equalsIgnoreCase(base.getHost()) || uri.getPort() != base.getPort()) {
            throw new GraphException(0, "invalid_url", "Refusing to call a non-Graph URL");
        }
        return uri;
    }

    private <T> T withRetry(Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                boolean retryable = status.value() == 429 || status.is5xxServerError();
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw new GraphException(
                            status.value(), graphErrorCode(e.getResponseBodyAsString()), "Graph call failed", e);
                }
                Duration wait = retryAfter(e.getResponseHeaders()).orElse(Duration.ofSeconds(attempt * 2L));
                LOG.warn(
                        "Graph returned {}; retrying in {} s (attempt {}/{})",
                        status.value(),
                        wait.toSeconds(),
                        attempt,
                        MAX_ATTEMPTS);
                sleep(wait);
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new GraphException(0, "unreachable", "Microsoft Graph could not be reached", e);
                }
                sleep(Duration.ofSeconds(attempt * 2L));
            }
        }
    }

    private String graphErrorCode(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            String code = error.isObject() ? error.path("code").asString("") : error.asString("");
            return code.isEmpty() ? "unknown" : code;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        if (headers == null || headers.getFirst(HttpHeaders.RETRY_AFTER) == null) {
            return Optional.empty();
        }
        try {
            Duration value = Duration.ofSeconds(
                    Long.parseLong(headers.getFirst(HttpHeaders.RETRY_AFTER).trim()));
            return Optional.of(value.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException(0, "interrupted", "Interrupted while waiting to retry Graph", e);
        }
    }
}
