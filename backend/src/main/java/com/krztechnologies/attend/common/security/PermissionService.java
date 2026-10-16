package com.krztechnologies.attend.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Effective permissions of a user, cached for 30 s. Changes to roles, access or an account publish
 * {@link PermissionsChangedEvent}, so a change takes effect on the next request.
 */
@Service
public class PermissionService {

    private final PermissionLookup lookup;
    private final Cache<UUID, Set<Permission>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public PermissionService(PermissionLookup lookup) {
        this.lookup = lookup;
    }

    public Set<Permission> permissionsOf(UUID employeeId) {
        return cache.get(employeeId, lookup::permissionsOf);
    }

    public boolean has(UUID employeeId, Permission permission) {
        return permissionsOf(employeeId).contains(permission);
    }

    public boolean hasAll(UUID employeeId, Collection<Permission> permissions) {
        return permissionsOf(employeeId).containsAll(permissions);
    }

    public boolean hasAny(UUID employeeId, Collection<Permission> permissions) {
        Set<Permission> own = permissionsOf(employeeId);
        return permissions.stream().anyMatch(own::contains);
    }

    /** Runs after the change has committed (or at once outside a transaction). */
    @TransactionalEventListener(fallbackExecution = true)
    void onPermissionsChanged(PermissionsChangedEvent event) {
        if (event.employeeId() == null) {
            cache.invalidateAll();
        } else {
            cache.invalidate(event.employeeId());
        }
    }
}
