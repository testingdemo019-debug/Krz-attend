package com.krztechnologies.attend.common.security;

import java.util.UUID;

/**
 * Someone's effective permissions may have changed (roles, access, account state). {@link PermissionService} and the
 * session filter's account check drop their cached values after the transaction commits.
 *
 * @param employeeId the affected user, or null when it may concern everyone (a role was edited or deleted)
 */
public record PermissionsChangedEvent(UUID employeeId) {

    public static PermissionsChangedEvent everyone() {
        return new PermissionsChangedEvent(null);
    }
}
