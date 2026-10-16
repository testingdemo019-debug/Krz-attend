package com.krztechnologies.attend.access.service;

import com.krztechnologies.attend.access.domain.Role;
import com.krztechnologies.attend.access.domain.UserAccess;
import com.krztechnologies.attend.access.repository.PermissionDefinitionRepository;
import com.krztechnologies.attend.access.repository.RoleRepository;
import com.krztechnologies.attend.access.repository.UserAccessRepository;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PermissionsChangedEvent;
import com.krztechnologies.attend.common.security.SessionRevoker;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles (Settings → Users &amp; roles → Roles). Built-in roles cannot be deleted; System Administrator is locked. A
 * custom role's code is {@code ROLE-<SLUG>} of its name. Every change is audited and takes effect at once.
 */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final UserAccessRepository access;
    private final PermissionDefinitionRepository catalogue;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;
    private final SessionRevoker sessions;
    private final EmployeeIdentityService identities;

    public RoleService(
            RoleRepository roles,
            UserAccessRepository access,
            PermissionDefinitionRepository catalogue,
            AuditService audit,
            CurrentUser currentUser,
            ApplicationEventPublisher events,
            SessionRevoker sessions,
            EmployeeIdentityService identities) {
        this.roles = roles;
        this.access = access;
        this.catalogue = catalogue;
        this.audit = audit;
        this.currentUser = currentUser;
        this.events = events;
        this.sessions = sessions;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public List<AccessViews.RoleView> list() {
        Map<Long, Long> members = new HashMap<>();
        access.countPerRole().forEach(row -> members.put((Long) row[0], (Long) row[1]));
        return roles.findAll().stream()
                .sorted(Comparator.comparing((Role r) -> !r.isBuiltIn())
                        .thenComparing(r -> r.getName().toLowerCase(Locale.ROOT)))
                .map(role -> toView(role, members.getOrDefault(role.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccessViews.PermissionView> catalogue() {
        return catalogue.findAllByOrderBySortOrderAsc().stream()
                .map(p ->
                        new AccessViews.PermissionView(p.getKey(), p.getGroupName(), p.getLabel(), p.getDescription()))
                .toList();
    }

    @Transactional
    public AccessViews.RoleView create(String name, String description, Set<Permission> granted) {
        String cleanName = requireName(name);
        requirePermissions(granted);
        if (roles.existsByNameIgnoreCase(cleanName)) {
            throw nameTaken();
        }
        Role role = roles.save(
                new Role(uniqueCode(cleanName), cleanName, clean(description), granted, currentUser.employeeId()));
        audit.record(
                AuditCategory.USERS_ROLES, "Created role", role.getName(), null, granted.size() + " permissions", null);
        return toView(role);
    }

    @Transactional
    public AccessViews.RoleView update(
            String code, String name, String description, Set<Permission> granted, int version) {
        Role role = find(code);
        if (role.isLocked()) {
            throw new Errors.Conflict(
                    "ROLE_LOCKED", "The System Administrator role always has every permission and can't be edited.");
        }
        if (role.getVersion() != version) {
            throw stale();
        }
        String cleanName = requireName(name);
        requirePermissions(granted);
        if (role.isBuiltIn() && !role.getName().equals(cleanName)) {
            throw new Errors.BusinessRule("BUILT_IN_NAME", "Built-in roles keep their name.");
        }
        if (!role.getName().equalsIgnoreCase(cleanName) && roles.existsByNameIgnoreCase(cleanName)) {
            throw nameTaken();
        }
        Set<Permission> before = role.permissions();
        role.update(cleanName, clean(description), granted);
        List<String> added = granted.stream()
                .filter(p -> !before.contains(p))
                .map(Permission::key)
                .sorted()
                .toList();
        List<String> removed = before.stream()
                .filter(p -> !granted.contains(p))
                .map(Permission::key)
                .sorted()
                .toList();
        audit.record(
                AuditCategory.USERS_ROLES,
                "Changed role permissions",
                role.getName(),
                removed.isEmpty() ? null : "Removed: " + String.join(", ", removed),
                added.isEmpty() ? null : "Added: " + String.join(", ", added),
                null);
        events.publishEvent(PermissionsChangedEvent.everyone());
        return toView(role);
    }

    /** Deletes a custom role; people left without any role lose access and are signed out. */
    @Transactional
    public void delete(String code) {
        Role role = find(code);
        if (role.isBuiltIn()) {
            throw new Errors.Conflict("BUILT_IN_ROLE", "Built-in roles can't be deleted.");
        }
        List<UserAccess> holders = access.findAllWithRole(code);
        List<String> stranded = new ArrayList<>();
        for (UserAccess holder : holders) {
            Set<Role> remaining = new HashSet<>(holder.getRoles());
            remaining.removeIf(r -> r.getCode().equals(code));
            if (remaining.isEmpty()) {
                identities
                        .find(holder.getEmployeeId())
                        .ifPresent(e -> stranded.add(e.objectId().toString()));
                access.delete(holder);
            } else {
                holder.replaceRoles(remaining);
            }
        }
        access.flush();
        roles.delete(role);
        audit.record(AuditCategory.USERS_ROLES, "Deleted role", role.getName(), holders.size() + " users", null, null);
        events.publishEvent(PermissionsChangedEvent.everyone());
        AfterCommit.run(() -> stranded.forEach(sessions::revokeAll));
    }

    Role find(String code) {
        return roles.findByCode(code).orElseThrow(() -> new Errors.NotFound("Role not found."));
    }

    AccessViews.RoleView toView(Role role) {
        return toView(role, role.getId() == null ? 0 : access.countWithRole(role.getId()));
    }

    private static AccessViews.RoleView toView(Role role, long members) {
        return new AccessViews.RoleView(
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.isBuiltIn(),
                role.isLocked(),
                role.permissions().stream().map(Permission::key).toList(),
                members,
                role.getCreatedByLabel(),
                role.getCreatedAt(),
                role.getVersion() == null ? 0 : role.getVersion());
    }

    private String uniqueCode(String name) {
        String slug =
                name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 26) {
            slug = slug.substring(0, 26).replaceAll("-+$", "");
        }
        if (slug.length() < 2) {
            slug = "CUSTOM";
        }
        String code = "ROLE-" + slug;
        for (int n = 2; roles.existsByCode(code); n++) {
            code = "ROLE-" + slug + "-" + n;
        }
        return code;
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new Errors.BusinessRule("NAME_REQUIRED", "Enter a name.");
        }
        return clean;
    }

    private static void requirePermissions(Set<Permission> granted) {
        if (granted == null || granted.isEmpty()) {
            throw new Errors.BusinessRule("NO_PERMISSIONS", "Give the role at least one permission.");
        }
    }

    private static String clean(String description) {
        return description == null ? "" : description.trim();
    }

    private static Errors.BusinessRule nameTaken() {
        return new Errors.BusinessRule("NAME_TAKEN", "Another role has this name.");
    }

    static Errors.Conflict stale() {
        return new Errors.Conflict("STALE_VERSION", "Someone else changed this. Reload to see the latest.");
    }
}
