package com.krztechnologies.attend.common.security;

import com.krztechnologies.attend.common.error.Errors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deny by default: every API handler must carry exactly one of {@link RequiresPermission}, {@link SignedIn} or
 * {@link PublicEndpoint}. A handler without a rule is refused at runtime (and fails the permission-matrix test).
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionInterceptor.class);

    private final CurrentUser currentUser;
    private final PermissionService permissions;

    public PermissionInterceptor(CurrentUser currentUser, PermissionService permissions) {
        this.currentUser = currentUser;
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        SignedIn signedIn = method.getMethodAnnotation(SignedIn.class);
        PublicEndpoint publicEndpoint = method.getMethodAnnotation(PublicEndpoint.class);
        long rules = Stream.of(required, signedIn, publicEndpoint)
                .filter(Objects::nonNull)
                .count();
        if (rules != 1) {
            LOG.error(
                    "Endpoint {} has {} permission rules; exactly one is required", method.getShortLogMessage(), rules);
            throw new Errors.Forbidden("ENDPOINT_NOT_GUARDED", "This endpoint is not available.");
        }
        if (publicEndpoint != null) {
            return true;
        }
        var principal =
                currentUser.principal().orElseThrow(() -> new Errors.Forbidden("NOT_SIGNED_IN", "Sign in first."));
        if (signedIn != null) {
            if (signedIn.requireAccess()
                    && permissions.permissionsOf(principal.employeeId()).isEmpty()) {
                throw new Errors.Forbidden("NO_ACCESS", "You don't have access to KRZ Attend.");
            }
            return true;
        }
        List<Permission> needed = Arrays.asList(required.value());
        boolean allowed = required.anyOf()
                ? permissions.hasAny(principal.employeeId(), needed)
                : permissions.hasAll(principal.employeeId(), needed);
        if (!allowed) {
            throw new Errors.Forbidden("FORBIDDEN", "You don't have permission to do this.");
        }
        return true;
    }
}
