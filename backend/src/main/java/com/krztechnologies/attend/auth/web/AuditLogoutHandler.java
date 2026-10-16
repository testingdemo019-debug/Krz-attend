package com.krztechnologies.attend.auth.web;

import com.krztechnologies.attend.auth.service.SignInService;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/** Audits "Signed out" before the session is invalidated by {@code POST /logout}. */
@Component
public class AuditLogoutHandler implements LogoutHandler {

    private final SignInService signIn;

    public AuditLogoutHandler(SignInService signIn) {
        this.signIn = signIn;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AttendPrincipal principal) {
            signIn.recordSignOut(principal.employeeId(), principal.displayName());
        }
    }
}
