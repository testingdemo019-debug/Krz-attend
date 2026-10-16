package com.krztechnologies.attend.common.graph;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.web.client.RestClient;

/**
 * The two uses of the Entra app registration "KRZ Attend":
 *
 * <ul>
 *   <li>{@code azure} — user sign-in, authorization code + PKCE, single-tenant issuer, principal name = {@code oid};
 *   <li>{@code graph} — app-only client credentials for Microsoft Graph (directory sync, sendMail).
 * </ul>
 *
 * Built from {@link EntraProperties} instead of issuer discovery, so start-up does not depend on reaching Microsoft and
 * the issuer is still validated on every sign-in. Every call to Microsoft (tokens, Graph) goes through one HTTP client
 * with explicit timeouts.
 */
@Configuration(proxyBeanMethods = false)
class EntraClientRegistrations {

    static final String SIGN_IN = "azure";
    static final String GRAPH = "graph";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            EntraProperties entra, @Value("${krz.entra.client-secret:}") String clientSecret) {
        ClientRegistration signIn = ClientRegistration.withRegistrationId(SIGN_IN)
                .clientId(entra.clientId())
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(entra.authorizationUri())
                .tokenUri(entra.tokenUri())
                .jwkSetUri(entra.jwkSetUri())
                .issuerUri(entra.issuerUri())
                .userNameAttributeName("oid")
                .clientName("Microsoft")
                .build();
        ClientRegistration graph = ClientRegistration.withRegistrationId(GRAPH)
                .clientId(entra.clientId())
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("https://graph.microsoft.com/.default")
                .tokenUri(entra.tokenUri())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();
        return new InMemoryClientRegistrationRepository(signIn, graph);
    }

    /** Timeouts for every call to Microsoft: 5 s to connect, 20 s to answer. */
    @Bean
    ClientHttpRequestFactory microsoftHttpRequestFactory() {
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(20))
                        .build())
                .build();
        // No automatic retries: GraphClient retries itself with Retry-After capped at 30 s.
        var factory = new HttpComponentsClientHttpRequestFactory(HttpClients.custom()
                .setConnectionManager(connections)
                .disableAutomaticRetries()
                .build());
        factory.setConnectionRequestTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    /** Exchanges the sign-in authorization code for tokens (used by oauth2Login). */
    @Bean
    RestClientAuthorizationCodeTokenResponseClient signInTokenResponseClient(
            ClientHttpRequestFactory microsoftHttpRequestFactory) {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(tokenRestClient(microsoftHttpRequestFactory));
        return client;
    }

    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository registrations) {
        return new InMemoryOAuth2AuthorizedClientService(registrations);
    }

    /** App-only tokens for Graph; cached until shortly before they expire. */
    @Bean
    OAuth2AuthorizedClientManager graphAuthorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients,
            ClientHttpRequestFactory microsoftHttpRequestFactory) {
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(tokenRestClient(microsoftHttpRequestFactory));
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(c -> c.accessTokenResponseClient(tokenClient))
                .build());
        return manager;
    }

    /** A RestClient set up the way Spring Security's token clients need it (form request, token JSON, OAuth2 errors). */
    private static RestClient tokenRestClient(ClientHttpRequestFactory factory) {
        return RestClient.builder()
                .requestFactory(factory)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
