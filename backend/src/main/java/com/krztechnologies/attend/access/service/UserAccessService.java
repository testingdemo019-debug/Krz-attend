package com.krztechnologies.attend.access.service;

import com.krztechnologies.attend.access.domain.Role;
import com.krztechnologies.attend.access.domain.UserAccess;
import com.krztechnologies.attend.access.repository.RoleRepository;
import com.krztechnologies.attend.access.repository.UserAccessRepository;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PermissionsChangedEvent;
import com.krztechnologies.attend.common.security.SessionRevoker;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who has access and with which roles (Settings → Users &amp; roles → Users). There must always be at least one
 * System Administrator with an enabled account, and nobody can remove their own administrator role.
 */
@Service
public class UserAccessService {

    static final String BOOTSTRAP_LABEL = "System (first administrator)";

    private final UserAccessRepository access;
    private final RoleRepository roles;
    private final EmployeeIdentityService identities;
    private final EmployeeDirectory directory;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;
    private final SessionRevoker sessions;
    private final TimeService time;
    private final JdbcClient jdbc;

    public UserAccessService(
            UserAccessRepository access,
            RoleRepository roles,
            EmployeeIdentityService identities,
            EmployeeDirectory directory,
            AuditService audit,
            CurrentUser currentUser,
            ApplicationEventPublisher events,
            SessionRevoker sessions,
            TimeService time,
            JdbcClient jdbc) {
        this.access = access;
        this.roles = roles;
        this.identities = identities;
        this.directory = directory;
        this.audit = audit;
        this.currentUser = currentUser;
        this.events = events;
        this.sessions = sessions;
        this.time = time;
        this.jdbc = jdbc;
    }

    /** Sort keys of the users table: user (default), roles, last sign-in and access given. */
    static final Map<String, Comparator<AccessViews.UserView>> SORTS = Map.of(
            "user", text(AccessViews.UserView::displayName),
            "roles", text(UserAccessService::roleNames),
            "last",
                    Comparator.comparing(
                            AccessViews.UserView::lastSignInAt, Comparator.nullsFirst(Comparator.naturalOrder())),
            "given", Comparator.comparing(AccessViews.UserView::addedAt));

    /**
     * The users table's filters (null or empty lists = any).
     *
     * @param roles role codes (someone with any of them)
     * @param accounts {@code enabled} and/or {@code disabled}
     * @param q name, role names and, with people.view.sensitive, the work e-mail
     */
    public record UserFilter(
            @Nullable List<String> roles,
            @Nullable List<String> accounts,
            @Nullable String q) {}

    /** One page of the people with access; @param sort a key of {@link #SORTS} with ,asc or ,desc (default user) */
    @Transactional(readOnly = true)
    public PageResponse<AccessViews.UserView> list(
            UserFilter filter, @Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        Pageable pageable = pageable(page, size, sort);
        List<AccessViews.UserView> rows = rows(filter, pageable.getSort());
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageResponse<>(
                rows.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), rows.size());
    }

    /** Everyone of the filter in the table's order (CSV). */
    @Transactional(readOnly = true)
    public List<AccessViews.UserView> export(UserFilter filter, @Nullable String sort) {
        return rows(filter, pageable(0, 1, sort).getSort());
    }

    /** The filter options with their counts over everyone with access (every role, the two account states). */
    @Transactional(readOnly = true)
    public AccessViews.UserFacets facets() {
        List<AccessViews.UserView> all = all();
        return new AccessViews.UserFacets(
                roles.findAll().stream()
                        .sorted(Comparator.comparing(Role::getCreatedAt).thenComparing(Role::getCode))
                        .map(r -> new AccessViews.Option(r.getCode(), r.getName(), (int) all.stream()
                                .filter(u -> u.roles().stream()
                                        .anyMatch(ref -> ref.code().equals(r.getCode())))
                                .count()))
                        .toList(),
                List.of(
                        new AccessViews.Option("enabled", "Enabled", (int) all.stream()
                                .filter(AccessViews.UserView::accountEnabled)
                                .count()),
                        new AccessViews.Option("disabled", "Disabled", (int)
                                all.stream().filter(u -> !u.accountEnabled()).count())));
    }

    private List<AccessViews.UserView> rows(UserFilter filter, Sort sort) {
        Sort.Order order = sort.iterator().next();
        Comparator<AccessViews.UserView> comparator = SORTS.get(order.getProperty());
        String q = filter.q() == null || filter.q().isBlank()
                ? null
                : filter.q().trim().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(u -> isEmpty(filter.roles())
                        || u.roles().stream().anyMatch(r -> filter.roles().contains(r.code())))
                .filter(u -> isEmpty(filter.accounts())
                        || filter.accounts().contains(u.accountEnabled() ? "enabled" : "disabled"))
                .filter(u -> q == null || searchText(u).contains(q))
                .sorted((order.isAscending() ? comparator : comparator.reversed())
                        .thenComparing(text(AccessViews.UserView::displayName)))
                .toList();
    }

    private List<AccessViews.UserView> all() {
        List<UserAccess> all = access.findAll();
        var summaries =
                directory.summaries(all.stream().map(UserAccess::getEmployeeId).toList());
        boolean sensitive = currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE);
        return all.stream()
                .filter(a -> summaries.containsKey(a.getEmployeeId()))
                .map(a -> {
                    var e = summaries.get(a.getEmployeeId());
                    return new AccessViews.UserView(
                            a.getEmployeeId(),
                            e.displayName(),
                            sensitive ? e.upn() : null,
                            e.employeeNumber(),
                            e.designation(),
                            e.accountEnabled(),
                            roleRefs(a.getRoles()),
                            a.getAddedByLabel(),
                            a.getAddedAt(),
                            e.lastSignInAt(),
                            a.getVersion());
                })
                .toList();
    }

    /** The prototype's search: the name, the e-mail (shown only with people.view.sensitive) and the role names. */
    private static String searchText(AccessViews.UserView u) {
        return (u.displayName() + " " + (u.email() == null ? "" : u.email()) + " " + roleNames(u))
                .toLowerCase(Locale.ROOT);
    }

    /** The roles in the table's words: names joined with commas. */
    public static String roleNames(AccessViews.UserView u) {
        return u.roles().stream().map(AccessViews.RoleRef::name).collect(Collectors.joining(","));
    }

    private static Pageable pageable(@Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        Map<String, String> fields =
                SORTS.keySet().stream().collect(Collectors.toMap(Function.identity(), Function.identity()));
        return PageRequestSpec.toPageable(page, size, sort, fields, Sort.by("user"));
    }

    private static boolean isEmpty(@Nullable List<String> values) {
        return values == null || values.isEmpty();
    }

    private static <T> Comparator<T> text(Function<T, String> value) {
        return Comparator.comparing(value, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /** "Give access" to someone without it. */
    @Transactional
    public void grant(UUID employeeId, Collection<String> roleCodes) {
        Set<Role> granted = resolveRoles(roleCodes);
        var employee = identities.find(employeeId).orElseThrow(() -> new Errors.NotFound("Employee not found."));
        createAccess(employeeId, granted);
        audit.record(AuditCategory.USERS_ROLES, "Gave access", employee.displayName(), null, names(granted), null);
    }

    /** Creates the access row (the caller audits). */
    @Transactional
    public void createAccess(UUID employeeId, Set<Role> granted) {
        if (access.existsById(employeeId)) {
            throw new Errors.Conflict(
                    "ALREADY_HAS_ACCESS", "This person already has access. Change their roles instead.");
        }
        var actor = currentUser.require();
        access.save(new UserAccess(employeeId, actor.employeeId(), actor.displayName(), time.now(), granted));
        events.publishEvent(new PermissionsChangedEvent(employeeId));
    }

    @Transactional
    public void updateRoles(UUID employeeId, Collection<String> roleCodes, int version) {
        UserAccess entry =
                access.findById(employeeId).orElseThrow(() -> new Errors.NotFound("This person has no access."));
        if (entry.getVersion() != version) {
            throw RoleService.stale();
        }
        Set<Role> granted = resolveRoles(roleCodes);
        protectAdministrators(entry, granted);
        String before = names(entry.getRoles());
        entry.replaceRoles(granted);
        String name = identities
                .find(employeeId)
                .map(EmployeeIdentityService.Identity::displayName)
                .orElse("—");
        audit.record(AuditCategory.USERS_ROLES, "Changed roles", name, before, names(granted), null);
        events.publishEvent(new PermissionsChangedEvent(employeeId));
    }

    /** Removes all access; the person is signed out at once. */
    @Transactional
    public void remove(UUID employeeId) {
        UserAccess entry =
                access.findById(employeeId).orElseThrow(() -> new Errors.NotFound("This person has no access."));
        protectAdministrators(entry, Set.of());
        var employee = identities.find(employeeId).orElseThrow(() -> new Errors.NotFound("Employee not found."));
        access.delete(entry);
        audit.record(
                AuditCategory.USERS_ROLES,
                "Removed access",
                employee.displayName(),
                names(entry.getRoles()),
                "No access",
                null);
        events.publishEvent(new PermissionsChangedEvent(employeeId));
        AfterCommit.run(() -> sessions.revokeAll(employee.objectId().toString()));
    }

    @Transactional(readOnly = true)
    public boolean hasAccess(UUID employeeId) {
        return access.findById(employeeId).map(a -> !a.getRoles().isEmpty()).orElse(false);
    }

    /**
     * First administrator (security §2): while NO System Administrator exists, a person listed in
     * BOOTSTRAP_ADMIN_EMAILS receives the role at sign-in. Serialised with an advisory lock; audited.
     *
     * @return true if the role was granted now
     */
    @Transactional
    public boolean grantBootstrapAdministrator(UUID employeeId, String displayName) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('attend.bootstrap_admin'))")
                .query()
                .singleRow();
        if (!access.findAllWithRole(Role.SYSTEM_ADMINISTRATOR).isEmpty()) {
            return false;
        }
        Role admin = roles.findByCode(Role.SYSTEM_ADMINISTRATOR)
                .orElseThrow(() -> new IllegalStateException("Built-in role ROLE-SYSADMIN is missing"));
        UserAccess entry = access.findById(employeeId)
                .orElseGet(() -> new UserAccess(employeeId, null, BOOTSTRAP_LABEL, time.now(), Set.of()));
        Set<Role> granted = new LinkedHashSet<>(entry.getRoles());
        granted.add(admin);
        entry.replaceRoles(granted);
        access.save(entry);
        audit.record(
                AuditActor.SYSTEM,
                AuditCategory.USERS_ROLES,
                "Gave access",
                displayName,
                null,
                "System Administrator (first administrator, BOOTSTRAP_ADMIN_EMAILS)",
                null);
        events.publishEvent(new PermissionsChangedEvent(employeeId));
        return true;
    }

    @Transactional(readOnly = true)
    public boolean administratorExists() {
        return !access.findAllWithRole(Role.SYSTEM_ADMINISTRATOR).isEmpty();
    }

    Set<Role> resolveRoles(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new Errors.BusinessRule("NO_ROLES", "Choose at least one role.");
        }
        Set<Role> resolved = new LinkedHashSet<>();
        for (String code : roleCodes) {
            resolved.add(roles.findByCode(code)
                    .orElseThrow(() -> new Errors.BusinessRule("UNKNOWN_ROLE", "Unknown role " + code + ".")));
        }
        return resolved;
    }

    /** The prototype's roleProtection: no removing your own administrator role, never the last administrator. */
    private void protectAdministrators(UserAccess entry, Set<Role> newRoles) {
        boolean had = entry.hasRole(Role.SYSTEM_ADMINISTRATOR);
        boolean keeps = newRoles.stream().anyMatch(Role::isSystemAdministrator);
        if (!had || keeps) {
            return;
        }
        if (entry.getEmployeeId().equals(currentUser.employeeId())) {
            throw new Errors.Conflict(
                    "OWN_ADMIN_ROLE",
                    "You can’t remove your own System Administrator role. Ask another administrator.");
        }
        long enabledAdmins = access.findAllWithRole(Role.SYSTEM_ADMINISTRATOR).stream()
                .filter(a -> identities
                        .find(a.getEmployeeId())
                        .map(EmployeeIdentityService.Identity::accountEnabled)
                        .orElse(false))
                .count();
        if (enabledAdmins <= 1) {
            throw new Errors.Conflict(
                    "LAST_ADMIN", "This is the last System Administrator. Give the role to someone else first.");
        }
    }

    private static List<AccessViews.RoleRef> roleRefs(Set<Role> roles) {
        return roles.stream()
                .sorted(Comparator.comparing(Role::getName))
                .map(r -> new AccessViews.RoleRef(r.getCode(), r.getName()))
                .toList();
    }

    private static String names(Set<Role> roles) {
        return roles.stream().map(Role::getName).sorted().collect(Collectors.joining(", "));
    }
}
