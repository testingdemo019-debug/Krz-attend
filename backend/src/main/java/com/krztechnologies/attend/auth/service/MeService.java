package com.krztechnologies.attend.auth.service;

import com.krztechnologies.attend.access.service.AccessRequestService;
import com.krztechnologies.attend.access.service.AccessViews;
import com.krztechnologies.attend.auth.domain.AccessState;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PermissionLookup;
import com.krztechnologies.attend.common.security.PermissionService;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the signed-in user is: identity, access state, roles, permissions and session timing ({@code GET /me}). */
@Service
public class MeService {

    private final EmployeeIdentityService identities;
    private final EmployeeDirectory directory;
    private final AccessStateResolver states;
    private final PermissionService permissions;
    private final PermissionLookup lookup;
    private final AccessRequestService requests;
    private final SettingsService settings;
    private final AppProperties app;

    public MeService(
            EmployeeIdentityService identities,
            EmployeeDirectory directory,
            AccessStateResolver states,
            PermissionService permissions,
            PermissionLookup lookup,
            AccessRequestService requests,
            SettingsService settings,
            AppProperties app) {
        this.identities = identities;
        this.directory = directory;
        this.states = states;
        this.permissions = permissions;
        this.lookup = lookup;
        this.requests = requests;
        this.settings = settings;
        this.app = app;
    }

    /**
     * @param expiresAt when the session ends if nothing else happens: the earlier of idle and absolute expiry
     */
    public record SessionInfo(Instant signedInAt, Instant expiresAt, Instant absoluteExpiresAt, int idleTimeoutMin) {}

    /** The user's Entra identity (their own e-mail is always theirs to see). */
    public record IdentityInfo(
            UUID tenantId,
            UUID objectId,
            String upn,
            boolean accountEnabled,
            @Nullable Instant syncedAt) {}

    /**
     * @param designation and department from the employment record (the user menu and My account), null when missing
     * @param accessRequest the user's latest access request (shown on the access screens), or null
     */
    public record Me(
            UUID id,
            String name,
            String initials,
            String email,
            @Nullable String designation,
            @Nullable String department,
            AccessState accessState,
            List<String> roles,
            List<String> permissions,
            SessionInfo session,
            IdentityInfo identity,
            AccessViews.@Nullable AccessRequestView accessRequest) {}

    @Transactional(readOnly = true)
    public Me me(UUID employeeId, Instant signedInAt, Instant lastActivity) {
        var identity =
                identities.find(employeeId).orElseThrow(() -> new Errors.NotFound("Your account was not found."));
        int idleMin = settings.signIn().idleTimeoutMin();
        Instant absolute = signedInAt.plus(Duration.ofHours(app.sessionAbsoluteHours()));
        Instant idle = lastActivity.plus(Duration.ofMinutes(idleMin));
        List<String> keys = permissions.permissionsOf(employeeId).stream()
                .sorted()
                .map(Permission::key)
                .toList();
        var card = directory.cards(List.of(employeeId)).get(employeeId);
        return new Me(
                employeeId,
                identity.displayName(),
                initials(identity.displayName()),
                identity.mail() != null ? identity.mail() : identity.upn(),
                card == null ? null : card.designation(),
                card == null ? null : card.department(),
                states.resolve(employeeId),
                lookup.roleNamesOf(employeeId),
                keys,
                new SessionInfo(signedInAt, idle.isBefore(absolute) ? idle : absolute, absolute, idleMin),
                new IdentityInfo(
                        identity.tenantId(),
                        identity.objectId(),
                        identity.upn(),
                        identity.accountEnabled(),
                        identity.syncedAt()),
                requests.latestFor(employeeId).orElse(null));
    }

    /** Two letters from the display name ("Ananya Iyer" → "AI"), as the initials avatars use. */
    static String initials(String displayName) {
        String letters = Arrays.stream(displayName.trim().split("\\s+"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, 1).toUpperCase(Locale.ROOT))
                .collect(Collectors.joining());
        if (letters.length() >= 2) {
            return letters.charAt(0) + letters.substring(letters.length() - 1);
        }
        return letters.isEmpty() ? "?" : letters;
    }
}
