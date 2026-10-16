package com.krztechnologies.attend.auth.web;

import com.krztechnologies.attend.auth.domain.SessionKeys;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.validation.WorkEmail;
import com.krztechnologies.attend.common.web.ReturnToValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Starts the Microsoft sign-in with PKCE, adds the validated {@code login_hint} (a work e-mail in the allowed domain,
 * ≤ 254 characters; anything else is dropped) with {@code domain_hint}, and keeps a validated {@code returnTo} in the
 * session for after the sign-in.
 */
public class LoginHintAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final EntraProperties entra;

    public LoginHintAuthorizationRequestResolver(ClientRegistrationRepository registrations, EntraProperties entra) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        this.entra = entra;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest original) {
        if (original == null) {
            return null;
        }
        ReturnToValidator.validate(request.getParameter("returnTo"))
                .ifPresentOrElse(
                        path -> request.getSession().setAttribute(SessionKeys.RETURN_TO, path),
                        () -> request.getSession().removeAttribute(SessionKeys.RETURN_TO));
        String hint = request.getParameter("login_hint");
        if (!WorkEmail.isWorkEmail(hint, entra.allowedDomain())) {
            return original;
        }
        return OAuth2AuthorizationRequest.from(original)
                .additionalParameters(params -> {
                    params.put("login_hint", hint);
                    params.put("domain_hint", entra.allowedDomain());
                })
                .build();
    }
}
