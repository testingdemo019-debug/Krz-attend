package com.krztechnologies.attend.camera.client;

import com.krztechnologies.attend.camera.domain.RtspUrl;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The go2rtc API (basic auth, internal network only): registers a camera's sub stream under its code, so browsers
 * reach it only as {@code /media/api/ws?src=CAM-01} and never see the RTSP URL. {@code PUT /api/streams} creates or
 * replaces the stream (go2rtc also writes it to its config file, which the compose setup keeps in a tmpfs).
 */
@Component
public class MediaRelayClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final MediaRelayProperties relay;
    private final JsonMapper json;
    private final RestClient rest;

    public MediaRelayClient(MediaRelayProperties relay, JsonMapper json) {
        this.relay = relay;
        this.json = json;
        var factory = ClientHttpRequestFactoryBuilder.httpComponents()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)
                        .withRedirects(HttpRedirects.DONT_FOLLOW));
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(relay.user(), relay.password()))
                .build();
    }

    /** go2rtc could not be reached or refused the call; the message never contains a URL with credentials. */
    public static final class MediaRelayException extends RuntimeException {
        MediaRelayException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Adds the stream, or replaces it with a new source (e.g. after a password change). */
    public void register(String name, RtspUrl source) {
        URI uri = UriComponentsBuilder.fromUri(relay.url())
                .path("/api/streams")
                .queryParam("name", "{name}")
                .queryParam("src", "{src}")
                .encode()
                .buildAndExpand(name, source.withCredentials())
                .toUri();
        try {
            rest.put().uri(uri).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new MediaRelayException(
                    "go2rtc refused stream " + name + ": HTTP "
                            + e.getStatusCode().value(),
                    null);
        } catch (RestClientException e) {
            // Not chained: Spring's message would carry the request URL, whose src holds the encoded credentials.
            throw new MediaRelayException(
                    "go2rtc is not reachable at " + relay.url() + " ("
                            + e.getClass().getSimpleName() + ")",
                    null);
        }
    }

    /** The names of the streams go2rtc has now ({@code GET /api/streams} is an object keyed by stream name). */
    public Set<String> streamNames() {
        URI uri = UriComponentsBuilder.fromUri(relay.url())
                .path("/api/streams")
                .build()
                .toUri();
        try {
            String body = rest.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            Set<String> names = new HashSet<>();
            if (body != null && !body.isBlank()) {
                names.addAll(json.readTree(body).propertyNames());
            }
            return names;
        } catch (RestClientResponseException e) {
            throw new MediaRelayException(
                    "go2rtc refused the stream list: HTTP " + e.getStatusCode().value(), null);
        } catch (RestClientException | JacksonException e) {
            throw new MediaRelayException("go2rtc stream list not available at " + relay.url(), e);
        }
    }
}
