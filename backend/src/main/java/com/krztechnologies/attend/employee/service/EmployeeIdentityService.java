package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.security.PermissionsChangedEvent;
import com.krztechnologies.attend.common.security.SessionRevoker;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.employee.domain.Employee;
import com.krztechnologies.attend.employee.domain.FaceProfileHistory;
import com.krztechnologies.attend.employee.repository.EmployeeRepository;
import com.krztechnologies.attend.employee.repository.FaceProfileHistoryRepository;
import com.krztechnologies.attend.employee.repository.FaceProfileRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee identities from Microsoft Entra ID (R16): created and updated by directory sync or JIT at first sign-in,
 * disabled and re-enabled with their consequences (sessions revoked, face profile deactivated, erasure scheduled).
 */
@Service
public class EmployeeIdentityService {

    static final String DISABLED_REASON = "Microsoft account disabled";
    static final String REMOVED_REASON = "Removed from the directory";

    private final EmployeeRepository employees;
    private final FaceProfileRepository faceProfiles;
    private final FaceProfileHistoryRepository history;
    private final SessionRevoker sessions;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final TimeService time;
    private final ConfigPublisher config;

    public EmployeeIdentityService(
            EmployeeRepository employees,
            FaceProfileRepository faceProfiles,
            FaceProfileHistoryRepository history,
            SessionRevoker sessions,
            ApplicationEventPublisher events,
            AuditService audit,
            TimeService time,
            ConfigPublisher config) {
        this.employees = employees;
        this.faceProfiles = faceProfiles;
        this.history = history;
        this.sessions = sessions;
        this.events = events;
        this.audit = audit;
        this.time = time;
        this.config = config;
    }

    /**
     * An employee's identity as other modules see it.
     *
     * @param tenantId Entra tenant ID
     * @param objectId Entra object ID ({@code oid}) — also the session principal name
     */
    public record Identity(
            UUID id,
            UUID tenantId,
            UUID objectId,
            String displayName,
            String upn,
            String mail,
            boolean accountEnabled,
            LocalDate leftOn,
            Instant lastSignInAt,
            Instant syncedAt) {}

    /** One user as the directory reports it. */
    public record DirectoryEntry(
            UUID objectId,
            String displayName,
            String givenName,
            String surname,
            String upn,
            String mail,
            boolean accountEnabled) {}

    /** What a directory entry changed. */
    public enum SyncOutcome {
        ADDED,
        UPDATED,
        DISABLED,
        UNCHANGED
    }

    @Transactional(readOnly = true)
    public Optional<Identity> find(UUID employeeId) {
        return employees.findById(employeeId).map(EmployeeIdentityService::toIdentity);
    }

    @Transactional(readOnly = true)
    public Optional<Identity> findByEntraIdentity(UUID tenantId, UUID objectId) {
        return employees.findByTenantIdAndObjectId(tenantId, objectId).map(EmployeeIdentityService::toIdentity);
    }

    /** Creates or updates one employee from the directory, including disable and re-enable (R16). */
    @Transactional
    public SyncOutcome applyDirectoryEntry(UUID tenantId, DirectoryEntry entry, int eraseAfterDays) {
        Instant now = time.now();
        Optional<Employee> existing = employees.findByTenantIdAndObjectId(tenantId, entry.objectId());
        if (existing.isEmpty()) {
            Employee created =
                    new Employee(tenantId, entry.objectId(), entry.displayName(), entry.upn(), entry.accountEnabled());
            created.updateIdentity(entry.displayName(), entry.givenName(), entry.surname(), entry.upn(), entry.mail());
            if (!entry.accountEnabled()) {
                created.disable(DISABLED_REASON, time.today());
            }
            created.markSynced(now);
            employees.save(created);
            return SyncOutcome.ADDED;
        }
        Employee employee = existing.get();
        boolean changed = employee.updateIdentity(
                entry.displayName(), entry.givenName(), entry.surname(), entry.upn(), entry.mail());
        employee.markSynced(now);
        if (employee.isAccountEnabled() && !entry.accountEnabled()) {
            disable(employee, DISABLED_REASON, eraseAfterDays);
            return SyncOutcome.DISABLED;
        }
        if (!employee.isAccountEnabled() && entry.accountEnabled()) {
            reenable(employee);
            return SyncOutcome.UPDATED;
        }
        return changed ? SyncOutcome.UPDATED : SyncOutcome.UNCHANGED;
    }

    /** The user no longer appears in the directory (deleted, or out of scope): treated like a disabled account. */
    @Transactional
    public boolean applyRemoval(UUID tenantId, UUID objectId, int eraseAfterDays) {
        return employees
                .findByTenantIdAndObjectId(tenantId, objectId)
                .filter(Employee::isAccountEnabled)
                .map(employee -> {
                    disable(employee, REMOVED_REASON, eraseAfterDays);
                    return true;
                })
                .orElse(false);
    }

    /** First sign-in before the first sync: the identity comes from a JIT Graph lookup (or the ID token). */
    @Transactional
    public Identity createFromSignIn(UUID tenantId, DirectoryEntry entry) {
        Employee employee = new Employee(tenantId, entry.objectId(), entry.displayName(), entry.upn(), true);
        employee.updateIdentity(entry.displayName(), entry.givenName(), entry.surname(), entry.upn(), entry.mail());
        return toIdentity(employees.save(employee));
    }

    /** Object IDs of every enabled employee of the tenant (a full sync disables those Graph no longer lists). */
    @Transactional(readOnly = true)
    public List<UUID> enabledObjectIds(UUID tenantId) {
        return employees.findEnabledObjectIds(tenantId);
    }

    @Transactional(readOnly = true)
    public long countEnabled() {
        return employees.countByAccountEnabledTrue();
    }

    @Transactional
    public void recordSignIn(UUID employeeId) {
        employees.findById(employeeId).ifPresent(e -> e.markSignedIn(time.now()));
    }

    private void disable(Employee employee, String reason, int eraseAfterDays) {
        LocalDate today = time.today();
        employee.disable(reason, today);
        faceProfiles.findById(employee.getId()).ifPresent(profile -> {
            if (profile.getStatus().isActive()) {
                profile.deactivateForDisabledAccount(DISABLED_REASON, today.plusDays(eraseAfterDays));
                history.save(new FaceProfileHistory(
                        employee.getId(),
                        time.now(),
                        FaceProfileHistory.Action.DEACTIVATED,
                        null,
                        AuditActor.DIRECTORY_SYNC.label(),
                        "accountEnabled = false"));
                config.publishGalleryAfterCommit(employee.getId());
            }
        });
        audit.record(
                AuditActor.DIRECTORY_SYNC,
                AuditCategory.EMPLOYEES,
                "Account disabled",
                employee.getDisplayName(),
                "Enabled",
                reason,
                null);
        events.publishEvent(new PermissionsChangedEvent(employee.getId()));
        String principal = employee.getObjectId().toString();
        AfterCommit.run(() -> sessions.revokeAll(principal));
    }

    private void reenable(Employee employee) {
        employee.enable();
        faceProfiles.findById(employee.getId()).ifPresent(profile -> profile.cancelScheduledErasure());
        audit.record(
                AuditActor.DIRECTORY_SYNC,
                AuditCategory.EMPLOYEES,
                "Account re-enabled",
                employee.getDisplayName(),
                "Disabled",
                "Enabled",
                null);
        events.publishEvent(new PermissionsChangedEvent(employee.getId()));
    }

    private static Identity toIdentity(Employee e) {
        return new Identity(
                e.getId(),
                e.getTenantId(),
                e.getObjectId(),
                e.getDisplayName(),
                e.getUpn(),
                e.getMail(),
                e.isAccountEnabled(),
                e.getLeftOn(),
                e.getLastSignInAt(),
                e.getSyncedAt());
    }
}
