package com.krztechnologies.attend.auth.web;

import com.krztechnologies.attend.auth.domain.SessionKeys;
import com.krztechnologies.attend.auth.service.EntraOidcUserService;
import com.krztechnologies.attend.common.time.TimeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * After Microsoft: success stamps the session (signed-in time, activity) and redirects to the validated
 * {@code returnTo} or {@code /}; failure redirects to {@code /signin?error=<code>} with one of the known codes.
 */
@Component
public class LoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LoginHandlers.class);

    private static final Set<String> KNOWN_ERRORS = Set.of(
            EntraOidcUserService.WRONG_TENANT,
            EntraOidcUserService.GUEST,
            EntraOidcUserService.WRONG_DOMAIN,
            EntraOidcUserService.NOT_IN_SCOPE);

    private final TimeService time;

    public LoginHandlers(TimeService time) {
        this.time = time;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        HttpSession session = request.getSession();
        var now = time.now();
        session.setAttribute(SessionKeys.SIGNED_IN_AT, now);
        session.setAttribute(SessionKeys.LAST_USER_ACTIVITY, now);
        Object returnTo = session.getAttribute(SessionKeys.RETURN_TO);
        session.removeAttribute(SessionKeys.RETURN_TO);
        response.sendRedirect(returnTo instanceof String path ? path : "/");
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth
                        && KNOWN_ERRORS.contains(oauth.getError().getErrorCode())
                ? oauth.getError().getErrorCode()
                : EntraOidcUserService.GENERIC;
        if (EntraOidcUserService.GENERIC.equals(code)) {
            // Expired or replayed sign-in, invalid ID token, Microsoft unreachable … (never contains a token).
            LOG.warn("Sign-in failed: {}", exception.getMessage());
        }
        response.sendRedirect("/signin?error=" + code);
    }
}
