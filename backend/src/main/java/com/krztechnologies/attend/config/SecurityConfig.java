package com.krztechnologies.attend.config;

import com.krztechnologies.attend.auth.service.EntraOidcUserService;
import com.krztechnologies.attend.auth.web.AuditLogoutHandler;
import com.krztechnologies.attend.auth.web.LoginHandlers;
import com.krztechnologies.attend.auth.web.LoginHintAuthorizationRequestResolver;
import com.krztechnologies.attend.auth.web.SessionPolicyFilter;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.error.ErrorWriter;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.security.RateLimitFilter;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import io.lettuce.core.RedisURI;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security section §2–§5: Microsoft sign-in (authorization code + PKCE, single tenant), JSON 401/403 for the API
 * (never an HTML redirect), SPA CSRF (XSRF-TOKEN cookie → X-XSRF-TOKEN header), logout, security headers as defence
 * in depth behind nginx, rate limits, and the session policy (idle + absolute timeout). No CORS: everything is
 * same-origin. Deny by default: unknown paths are refused, and every API handler needs a permission rule
 * (PermissionInterceptor).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** CSP for backend responses (the SPA's CSP with the style nonce is set by nginx). */
    static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; "
            + "form-action 'self' https://login.microsoftonline.com";

    /** Development only (API docs on): Swagger UI loads its own scripts and styles and calls the API. */
    static final String SWAGGER_UI_CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            EntraProperties entra,
            EntraOidcUserService oidcUsers,
            LoginHandlers loginHandlers,
            AuditLogoutHandler logoutAudit,
            SessionPolicyFilter sessionPolicy,
            RateLimitFilter rateLimit,
            ErrorWriter errors,
            RestClientAuthorizationCodeTokenResponseClient signInTokenResponseClient,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocs)
            throws Exception {
        var api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        var swaggerUi = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"));
        http.authorizeHttpRequests(a -> {
                    a.requestMatchers("/api/v1/public/**", "/error").permitAll();
                    // Only on the internal management port 8081 (never published).
                    a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    a.requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**")
                            .permitAll();
                    if (apiDocs) {
                        a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll();
                    }
                    a.requestMatchers("/api/**").authenticated();
                    a.anyRequest().denyAll();
                })
                .oauth2Login(o -> o.loginPage("/signin")
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(
                                new LoginHintAuthorizationRequestResolver(registrations, entra)))
                        .authorizedClientRepository(new NoTokenStorage())
                        .tokenEndpoint(t -> t.accessTokenResponseClient(signInTokenResponseClient))
                        .userInfoEndpoint(u -> u.oidcUserService(oidcUsers))
                        .successHandler(loginHandlers)
                        .failureHandler(loginHandlers))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                                (request, response, ex) ->
                                        errors.write(response, 401, "NOT_SIGNED_IN", "Sign in first."),
                                api)
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(response, 403, "FORBIDDEN", "You don't have permission to do this.")))
                .csrf(c -> c.spa())
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .logout(l -> l.logoutUrl("/logout")
                        .addLogoutHandler(logoutAudit)
                        .invalidateHttpSession(true)
                        .deleteCookies("KRZSESSION")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .headers(h -> {
                    // A CSP writer only writes when no CSP is set yet, so the Swagger UI policy (dev) wins there.
                    if (apiDocs) {
                        h.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                swaggerUi, new ContentSecurityPolicyHeaderWriter(SWAGGER_UI_CSP)));
                    }
                    h.addHeaderWriter(new ContentSecurityPolicyHeaderWriter(CSP))
                            .referrerPolicy(r ->
                                    r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                            .permissionsPolicyHeader(
                                    p -> p.policy("camera=(self), microphone=(), geolocation=(), payment=(), usb=()"))
                            .crossOriginOpenerPolicy(c ->
                                    c.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                            .frameOptions(f -> f.deny())
                            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                    .maxAgeInSeconds(Duration.ofDays(365).toSeconds()));
                })
                .addFilterBefore(rateLimit, OAuth2AuthorizationRequestRedirectFilter.class)
                .addFilterAfter(sessionPolicy, AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    SessionPolicyFilter sessionPolicyFilter(
            TimeService time,
            SettingsService settings,
            AppProperties app,
            EmployeeIdentityService identities,
            ErrorWriter errors) {
        return new SessionPolicyFilter(time, settings, app, identities, errors);
    }

    @Bean
    RateLimitFilter rateLimitFilter(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.username}") String username,
            @Value("${spring.data.redis.password}") String password,
            ErrorWriter errors) {
        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withAuthentication(username, password.toCharArray())
                .withTimeout(Duration.ofSeconds(2))
                .build();
        return new RateLimitFilter(uri, errors);
    }

    /** These filters run inside the security chain only; Spring Boot must not also register them globally. */
    @Bean
    FilterRegistrationBean<SessionPolicyFilter> sessionPolicyRegistration(SessionPolicyFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Renders the CSRF token on every request so the XSRF-TOKEN cookie is always issued for the SPA. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * The backend never needs the users' Microsoft access or refresh tokens (Graph uses app-only credentials), so
     * they are not kept anywhere (security §3).
     */
    static final class NoTokenStorage implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String registrationId, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient client,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {
            // Deliberately not stored: see the class comment.
        }

        @Override
        public void removeAuthorizedClient(
                String registrationId,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {
            // Nothing is stored, so nothing to remove.
        }
    }
}
