package com.krztechnologies.attend.access.service;

import com.krztechnologies.attend.access.domain.Role;
import com.krztechnologies.attend.access.repository.UserAccessRepository;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PermissionLookup;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's effective permissions: the union of their roles' permissions (everything for System Administrator), and
 * nothing at all while their Microsoft account is disabled. Their access rows stay, so re-enabling restores access.
 * Reads only through repositories: it sits under the audit and settings services, so it must not depend on services
 * that audit (DECISIONS).
 */
@Service
public class AccessPermissionLookup implements PermissionLookup {

    private final UserAccessRepository access;

    public AccessPermissionLookup(UserAccessRepository access) {
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Permission> permissionsOf(UUID employeeId) {
        if (!access.isAccountEnabled(employeeId).orElse(false)) {
            return Set.of();
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        access.findById(employeeId).ifPresent(a -> a.getRoles().forEach(r -> permissions.addAll(r.permissions())));
        return Set.copyOf(permissions);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> roleNamesOf(UUID employeeId) {
        return access.findById(employeeId)
                .map(a -> a.getRoles().stream().map(Role::getName).sorted().toList())
                .orElse(List.of());
    }
}
