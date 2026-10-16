package com.krztechnologies.attend.privacy.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.privacy.domain.PrivacyRequest;
import com.krztechnologies.attend.privacy.repository.PrivacyQueries;
import com.krztechnologies.attend.privacy.repository.PrivacyRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Privacy &amp; retention → Requests from employees (access, correction, erasure): recorded as PR-nnn, due
 * 30 days after receipt (overdue ones are flagged for the UI), moved Open → In progress → Completed, or Declined with
 * a reason. Every step is audited (category Privacy). The erasure itself is done on the employee's profile.
 */
@Service
public class PrivacyRequestService {

    private final PrivacyRequestRepository requests;
    private final PrivacyQueries queries;
    private final IdGenerator ids;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final TimeService time;

    public PrivacyRequestService(
            PrivacyRequestRepository requests,
            PrivacyQueries queries,
            IdGenerator ids,
            CurrentUser currentUser,
            AuditService audit,
            TimeService time) {
        this.requests = requests;
        this.queries = queries;
        this.ids = ids;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    /** A person shown by name. */
    public record PersonRef(UUID id, String name) {}

    /**
     * One request.
     *
     * @param overdue still open (or in progress) after its due date
     * @param handledBy who last moved it on, or null
     */
    public record PrivacyRequestView(
            String code,
            PersonRef employee,
            String type,
            String status,
            LocalDate receivedOn,
            LocalDate dueOn,
            boolean overdue,
            @Nullable String note,
            @Nullable PersonRef handledBy,
            @Nullable Instant completedAt,
            @Nullable String outcome,
            int version) {}

    @Transactional(readOnly = true)
    public List<PrivacyRequestView> list(PrivacyRequest.Status status) {
        List<PrivacyRequest> all = status == null
                ? requests.findAllByOrderByReceivedOnDescIdDesc()
                : requests.findByStatusOrderByReceivedOnDescIdDesc(status);
        Set<UUID> people = new HashSet<>();
        all.forEach(r -> Stream.of(r.getEmployeeId(), r.getHandledBy())
                .filter(Objects::nonNull)
                .forEach(people::add));
        Map<UUID, String> names = queries.names(people);
        LocalDate today = time.today();
        return all.stream().map(r -> toView(r, names, today)).toList();
    }

    /**
     * Records a request received from an employee.
     *
     * @param receivedOn defaults to today; never in the future
     */
    @Transactional
    public PrivacyRequestView create(UUID employeeId, PrivacyRequest.Type type, LocalDate receivedOn, String note) {
        LocalDate today = time.today();
        LocalDate received = receivedOn == null ? today : receivedOn;
        if (received.isAfter(today)) {
            throw new Errors.BusinessRule("RECEIVED_IN_FUTURE", "The date received can't be in the future.");
        }
        Map<UUID, String> names = queries.names(Set.of(employeeId));
        if (!names.containsKey(employeeId)) {
            throw new Errors.BusinessRule("UNKNOWN_EMPLOYEE", "Choose an employee from the directory.");
        }
        PrivacyRequest request = requests.save(new PrivacyRequest(
                ids.next(CodeType.PRIVACY_REQUEST), employeeId, type, received, blankToNull(note), time.now()));
        audit.record(
                AuditCategory.PRIVACY,
                "Recorded privacy request",
                request.getCode(),
                null,
                type.label() + " · " + names.get(employeeId) + " · due " + request.getDueOn(),
                Map.of("employeeId", employeeId.toString()));
        return toView(request, names, today);
    }

    /** Moves a request on (409 STALE_VERSION when someone else changed it first). */
    @Transactional
    public PrivacyRequestView update(String code, PrivacyRequest.Status next, String outcome, int version) {
        PrivacyRequest request =
                requests.findByCode(code).orElseThrow(() -> new Errors.NotFound("Privacy request not found."));
        if (request.getVersion() != version) {
            throw new Errors.Conflict("STALE_VERSION", "Someone else changed this request. Reload to see the latest.");
        }
        PrivacyRequest.Status before = request.getStatus();
        if (!request.canMoveTo(next)) {
            throw new Errors.BusinessRule(
                    "INVALID_STATUS_CHANGE",
                    "A request that is " + before.label().toLowerCase(Locale.ROOT) + " can't become "
                            + next.label().toLowerCase(Locale.ROOT) + ".");
        }
        String reason = blankToNull(outcome);
        if (next == PrivacyRequest.Status.DECLINED && reason == null) {
            throw new Errors.BusinessRule("REASON_REQUIRED", "Give the reason for declining the request.");
        }
        request.moveTo(next, reason, currentUser.employeeId(), time.now());
        PrivacyRequest saved = requests.saveAndFlush(request);
        audit.record(
                AuditCategory.PRIVACY,
                "Updated privacy request",
                saved.getCode(),
                before.label(),
                reason == null ? next.label() : next.label() + " — " + reason,
                null);
        Set<UUID> people = new HashSet<>(List.of(saved.getEmployeeId(), saved.getHandledBy()));
        return toView(saved, queries.names(people), time.today());
    }

    private static PrivacyRequestView toView(PrivacyRequest r, Map<UUID, String> names, LocalDate today) {
        return new PrivacyRequestView(
                r.getCode(),
                new PersonRef(r.getEmployeeId(), names.get(r.getEmployeeId())),
                r.getType().name(),
                r.getStatus().name(),
                r.getReceivedOn(),
                r.getDueOn(),
                !r.getStatus().isFinal() && r.getDueOn().isBefore(today),
                r.getNote(),
                r.getHandledBy() == null ? null : new PersonRef(r.getHandledBy(), names.get(r.getHandledBy())),
                r.getCompletedAt(),
                r.getOutcome(),
                r.getVersion());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
