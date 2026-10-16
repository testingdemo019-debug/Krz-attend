package com.krztechnologies.attend.common.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads what a user may do from the access module (implemented there; {@code common} depends on no feature module).
 */
public interface PermissionLookup {

    /** Every permission of the user's roles; all permissions if a role is the locked System Administrator. */
    Set<Permission> permissionsOf(UUID employeeId);

    /** Role names of the user, for audit entries. */
    List<String> roleNamesOf(UUID employeeId);
}
