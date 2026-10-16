package com.krztechnologies.attend.common.security;

import com.krztechnologies.attend.common.error.Errors;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The signed-in user of the current request, and their permissions. */
@Component
public class CurrentUser {

    private final PermissionService permissions;

    public CurrentUser(PermissionService permissions) {
        this.permissions = permissions;
    }

    /** The principal, if the request is authenticated. */
    public Optional<AttendPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AttendPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** The signed-in principal; 401-equivalent error when there is none (should not happen behind the filter chain). */
    public AttendPrincipal require() {
        return principal().orElseThrow(() -> new Errors.Forbidden("NOT_SIGNED_IN", "Sign in first."));
    }

    public UUID employeeId() {
        return require().employeeId();
    }

    public boolean has(Permission permission) {
        return principal().map(p -> permissions.has(p.employeeId(), permission)).orElse(false);
    }
}
