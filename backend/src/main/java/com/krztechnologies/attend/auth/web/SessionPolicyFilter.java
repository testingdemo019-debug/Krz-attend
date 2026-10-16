package com.krztechnologies.attend.auth.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.krztechnologies.attend.auth.domain.SessionKeys;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.error.ErrorWriter;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.PermissionsChangedEvent;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Session rules for signed-in API calls (security §3):
 *
 * <ul>
 *   <li>absolute lifetime SESSION_ABSOLUTE_HOURS from sign-in;
 *   <li>idle timeout (Settings, default 30 min) measured against {@code lastUserActivity}, which only state-changing
 *       requests, {@code POST /me/keepalive} and requests with {@code X-User-Activity: 1} refresh — never SSE, polling or
 *       background refetches;
 *   <li>the Microsoft account must still be enabled (re-checked at most every 60 s).
 * </ul>
 *
 * On expiry the session is invalidated and the call answers 401 {@code SESSION_EXPIRED}. Created by the security
 * configuration and placed inside the security filter chain (not registered as a plain servlet filter).
 */
public class SessionPolicyFilter extends OncePerRequestFilter {

    public static final String USER_ACTIVITY_HEADER = "X-User-Activity";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String KEEPALIVE = "/api/v1/me/keepalive";

    private final TimeService time;
    private final SettingsService settings;
    private final AppProperties app;
    private final EmployeeIdentityService identities;
    private final ErrorWriter errors;
    private final Cache<UUID, Boolean> enabled = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public SessionPolicyFilter(
            TimeService time,
            SettingsService settings,
            AppProperties app,
            EmployeeIdentityService identities,
            ErrorWriter errors) {
        this.time = time;
        this.settings = settings;
        this.app = app;
        this.identities = identities;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (session == null
                || authentication == null
                || !(authentication.getPrincipal() instanceof AttendPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        Instant now = time.now();
        Instant signedInAt = (Instant) session.getAttribute(SessionKeys.SIGNED_IN_AT);
        Instant lastActivity = (Instant) session.getAttribute(SessionKeys.LAST_USER_ACTIVITY);
        Duration idle = Duration.ofMinutes(settings.signIn().idleTimeoutMin());
        boolean absoluteExpired = signedInAt == null
                || signedInAt.plus(Duration.ofHours(app.sessionAbsoluteHours())).isBefore(now);
        boolean idleExpired = lastActivity == null || lastActivity.plus(idle).isBefore(now);
        if (absoluteExpired || idleExpired) {
            end(session, response, "SESSION_EXPIRED", "Your session has expired. Sign in again.");
            return;
        }
        Boolean accountEnabled = enabled.get(
                principal.employeeId(),
                id -> identities
                        .find(id)
                        .map(EmployeeIdentityService.Identity::accountEnabled)
                        .orElse(false));
        if (!Boolean.TRUE.equals(accountEnabled)) {
            end(session, response, "ACCOUNT_DISABLED", "Your Microsoft account is disabled.");
            return;
        }
        if (isUserActivity(request)) {
            session.setAttribute(SessionKeys.LAST_USER_ACTIVITY, now);
        }
        MDC.put("userId", principal.employeeId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
        }
    }

    /** A disabled or re-enabled account is re-checked on its very next request, not after the cache expires. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onPermissionsChanged(PermissionsChangedEvent event) {
        if (event.employeeId() == null) {
            enabled.invalidateAll();
        } else {
            enabled.invalidate(event.employeeId());
        }
    }

    private static boolean isUserActivity(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod())
                || KEEPALIVE.equals(request.getRequestURI())
                || "1".equals(request.getHeader(USER_ACTIVITY_HEADER));
    }

    private void end(HttpSession session, HttpServletResponse response, String code, String message)
            throws IOException {
        session.invalidate();
        SecurityContextHolder.clearContext();
        errors.write(response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }
}
