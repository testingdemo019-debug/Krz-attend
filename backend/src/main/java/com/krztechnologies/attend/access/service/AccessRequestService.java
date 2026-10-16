package com.krztechnologies.attend.access.service;

import com.krztechnologies.attend.access.domain.AccessRequest;
import com.krztechnologies.attend.access.domain.Role;
import com.krztechnologies.attend.access.repository.AccessRequestRepository;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.realtime.SseHub;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access requests: a signed-in person without a role asks for access (one pending at a time, NR-06; RateLimitFilter
 * allows 3 attempts a day);
 * an administrator approves with roles or declines with a reason the person sees on the sign-in screen.
 */
@Service
public class AccessRequestService {

    private final AccessRequestRepository requests;
    private final UserAccessService access;
    private final EmployeeIdentityService identities;
    private final EmployeeDirectory directory;
    private final NotificationService notifications;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final IdGenerator ids;
    private final TimeService time;
    private final SseHub sse;

    public AccessRequestService(
            AccessRequestRepository requests,
            UserAccessService access,
            EmployeeIdentityService identities,
            EmployeeDirectory directory,
            NotificationService notifications,
            AuditService audit,
            CurrentUser currentUser,
            IdGenerator ids,
            TimeService time,
            SseHub sse) {
        this.requests = requests;
        this.access = access;
        this.identities = identities;
        this.directory = directory;
        this.notifications = notifications;
        this.audit = audit;
        this.currentUser = currentUser;
        this.ids = ids;
        this.time = time;
        this.sse = sse;
    }

    /** The signed-in user asks for access. */
    @Transactional
    public AccessViews.AccessRequestView submit(String note) {
        var me = currentUser.require();
        UUID employeeId = me.employeeId();
        if (access.hasAccess(employeeId)) {
            throw new Errors.Conflict("ALREADY_HAS_ACCESS", "You already have access to KRZ Attend.");
        }
        Optional<AccessRequest> latest = requests.findFirstByEmployeeIdOrderByCreatedAtDesc(employeeId);
        if (latest.isPresent() && latest.get().getStatus() == AccessRequest.Status.PENDING) {
            throw new Errors.Conflict("REQUEST_PENDING", "Your request is already waiting for an administrator.");
        }
        String clean = note == null || note.isBlank() ? null : note.trim();
        AccessRequest request =
                requests.save(new AccessRequest(ids.next(CodeType.ACCESS_REQUEST), employeeId, clean, time.now()));
        audit.record(AuditCategory.USERS_ROLES, "Requested access", request.getCode(), null, "Pending", null);
        notifications.publish(
                "NR-06",
                new NotificationRequest(
                        "Access request from " + me.displayName(),
                        clean,
                        "/settings/users?tab=requests",
                        "NR-06:" + request.getCode(),
                        Map.of("requestCode", request.getCode())));
        broadcastPendingCount();
        return toView(request);
    }

    @Transactional(readOnly = true)
    public List<AccessViews.AccessRequestView> list(AccessRequest.Status status) {
        List<AccessRequest> found = status == null
                ? requests.findAllByOrderByCreatedAtDesc()
                : requests.findByStatusOrderByCreatedAtDesc(status);
        var summaries = directory.summaries(found.stream()
                .flatMap(r -> Stream.of(r.getEmployeeId(), r.getDecidedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return found.stream().map(r -> toView(r, summaries)).toList();
    }

    /** The user's own latest request (shown on the access screens). */
    @Transactional(readOnly = true)
    public Optional<AccessViews.AccessRequestView> latestFor(UUID employeeId) {
        return requests.findFirstByEmployeeIdOrderByCreatedAtDesc(employeeId).map(this::toView);
    }

    @Transactional
    public void approve(String code, Collection<String> roleCodes) {
        AccessRequest request = pending(code);
        Set<Role> granted = access.resolveRoles(roleCodes);
        access.createAccess(request.getEmployeeId(), granted);
        request.approve(currentUser.employeeId(), time.now());
        String name = nameOf(request.getEmployeeId());
        audit.record(
                AuditCategory.USERS_ROLES,
                "Approved access request",
                name,
                "Pending",
                String.join(", ", granted.stream().map(Role::getName).sorted().toList()),
                Map.of("request", request.getCode()));
        broadcastPendingCount();
    }

    @Transactional
    public void decline(String code, String reason) {
        AccessRequest request = pending(code);
        if (reason == null || reason.isBlank()) {
            throw new Errors.BusinessRule(
                    "REASON_REQUIRED", "Give a reason. The person sees it on the sign-in screen.");
        }
        request.decline(currentUser.employeeId(), time.now(), reason.trim());
        audit.record(
                AuditCategory.USERS_ROLES,
                "Declined access request",
                nameOf(request.getEmployeeId()),
                "Pending",
                "Declined — " + reason.trim(),
                Map.of("request", request.getCode()));
        broadcastPendingCount();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return requests.countByStatus(AccessRequest.Status.PENDING);
    }

    /** Who is waiting for access, oldest request first (the Dashboard's alert). */
    @Transactional(readOnly = true)
    public List<String> pendingNames() {
        return list(AccessRequest.Status.PENDING).reversed().stream()
                .map(AccessViews.AccessRequestView::displayName)
                .toList();
    }

    private AccessRequest pending(String code) {
        AccessRequest request =
                requests.findByCode(code).orElseThrow(() -> new Errors.NotFound("Access request not found."));
        if (request.getStatus() != AccessRequest.Status.PENDING) {
            throw new Errors.Conflict(
                    "ALREADY_DECIDED",
                    "This request was already " + request.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return request;
    }

    private void broadcastPendingCount() {
        AfterCommit.run(
                () -> sse.publish("access.requests", Map.of("pending", pendingCount()), Permission.SETTINGS_ROLES));
    }

    private String nameOf(UUID employeeId) {
        return identities
                .find(employeeId)
                .map(EmployeeIdentityService.Identity::displayName)
                .orElse("—");
    }

    private AccessViews.AccessRequestView toView(AccessRequest r) {
        var people = Stream.of(r.getEmployeeId(), r.getDecidedBy())
                .filter(Objects::nonNull)
                .toList();
        return toView(r, directory.summaries(people));
    }

    private static AccessViews.AccessRequestView toView(
            AccessRequest r, Map<UUID, EmployeeDirectory.EmployeeSummary> people) {
        var person = people.get(r.getEmployeeId());
        var decider = r.getDecidedBy() == null ? null : people.get(r.getDecidedBy());
        return new AccessViews.AccessRequestView(
                r.getCode(),
                r.getEmployeeId(),
                person == null ? "—" : person.displayName(),
                person == null ? null : person.employeeNumber(),
                person == null ? null : person.designation(),
                person == null ? null : person.department(),
                r.getNote(),
                r.getStatus().name(),
                r.getCreatedAt(),
                decider == null ? null : decider.displayName(),
                r.getDecidedAt(),
                r.getDecisionReason());
    }
}
