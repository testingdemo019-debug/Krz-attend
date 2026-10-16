package com.krztechnologies.attend.access.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models returned by the access services (records; mapped to the API as they are). */
public final class AccessViews {

    private AccessViews() {}

    /** A role as a short reference. */
    public record RoleRef(String code, String name) {}

    /**
     * A role for Settings → Users &amp; roles.
     *
     * @param permissions permission keys (all 19 for the locked System Administrator)
     * @param users number of people with this role
     */
    public record RoleView(
            String code,
            String name,
            String description,
            boolean builtIn,
            boolean locked,
            List<String> permissions,
            long users,
            @Nullable String createdBy,
            Instant createdAt,
            int version) {}

    /**
     * A person with access.
     *
     * @param email work e-mail; only for viewers with people.view.sensitive, otherwise null
     * @param employeeNumber HRMS Employee ID, null when missing
     * @param designation the person cell's sub-line without people.view.sensitive
     * @param lastSignInAt null when they never signed in
     */
    public record UserView(
            UUID employeeId,
            String displayName,
            @Nullable String email,
            @Nullable String employeeNumber,
            @Nullable String designation,
            boolean accountEnabled,
            List<RoleRef> roles,
            String addedBy,
            Instant addedAt,
            @Nullable Instant lastSignInAt,
            int version) {}

    /** A filter value and how many rows have it. */
    public record Option(String value, String label, int count) {}

    /** The users table's filter options with their counts over everyone with access. */
    public record UserFacets(List<Option> role, List<Option> account) {}

    /** An access request (AR-nnnn); the designation and department make the person cell's sub-line. */
    public record AccessRequestView(
            String code,
            UUID employeeId,
            String displayName,
            @Nullable String employeeNumber,
            @Nullable String designation,
            @Nullable String department,
            @Nullable String note,
            String status,
            Instant createdAt,
            @Nullable String decidedBy,
            @Nullable Instant decidedAt,
            @Nullable String reason) {}

    /** A permission of the catalogue (role editor). */
    public record PermissionView(String key, String group, String label, String description) {}
}
