package com.krztechnologies.attend.hrms.service;

import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.hrms.repository.OutboxStore;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import com.krztechnologies.attend.rules.service.CrossingMethod;
import com.krztechnologies.attend.rules.service.Direction;
import com.krztechnologies.attend.rules.service.OutboxReconciliation;
import com.krztechnologies.attend.rules.service.OutboxReconciliation.Change;
import com.krztechnologies.attend.rules.service.OutboxReconciliation.ExistingPunch;
import com.krztechnologies.attend.rules.service.OutboxStatus;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.DesiredPunch;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.PunchPayload;
import com.krztechnologies.attend.rules.service.PunchType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * R8: brings the HRMS outbox of one (employee, shift day) in line with the punches the evaluated day should have, in
 * the caller's transaction (attendance recompute). Applies the pure plan of {@link OutboxReconciliation}: new rows
 * (blocked without an Employee ID, held while HRMS is off, else pending), payload and status updates of unsent rows,
 * sent punches voided with a VOID entry, unsent ones deleted. A punch that becomes blocked raises NR-07 (once per
 * employee and day).
 */
@Service
public class HrmsOutboxReconciler {

    private static final String KIND_PUNCH = "PUNCH";
    private static final String KIND_VOID = "VOID";

    private final OutboxStore store;
    private final EmployeeDirectory employees;
    private final IdGenerator ids;
    private final FeatureFlags features;
    private final NotificationService notifications;
    private final TimeService time;
    private final JsonMapper json;

    public HrmsOutboxReconciler(
            OutboxStore store,
            EmployeeDirectory employees,
            IdGenerator ids,
            FeatureFlags features,
            NotificationService notifications,
            TimeService time,
            JsonMapper json) {
        this.store = store;
        this.employees = employees;
        this.ids = ids;
        this.features = features;
        this.notifications = notifications;
        this.time = time;
        this.json = json;
    }

    /**
     * @param desired the punches of the evaluated day (none when it is not tracked or not applicable)
     * @return how many outbox rows changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reconcile(UUID employeeId, LocalDate day, List<DesiredPunch> desired) {
        List<OutboxStore.LivePunch> rows = store.livePunches(employeeId, day);
        if (desired.isEmpty() && rows.isEmpty()) {
            return 0;
        }
        var person = employees.summaries(List.of(employeeId)).get(employeeId);
        String hrmsEmployeeId = person == null ? null : person.employeeNumber();
        Map<String, OutboxStore.LivePunch> byCode = new LinkedHashMap<>();
        rows.forEach(r -> byCode.put(r.code(), r));
        List<ExistingPunch> existing = rows.stream()
                .map(r -> new ExistingPunch(
                        r.code(),
                        r.punchId(),
                        r.status(),
                        payload(r.payload()),
                        r.crossingExists()
                                ? new OutboxReconciliation.CrossingState(
                                        r.crossingEmployeeId(), r.voidReason(), r.flipReason())
                                : null))
                .toList();
        List<Change> plan = OutboxReconciliation.plan(
                employeeId, hrmsEmployeeId, features.hrmsEnabled(), time.zone(), desired, existing);
        Instant now = time.now();
        boolean blocked = false;
        for (Change change : plan) {
            blocked |= change.blocks();
            switch (change) {
                case OutboxReconciliation.Create c ->
                    store.insert(new OutboxStore.NewRow(
                            ids.next(CodeType.OUTBOX),
                            KIND_PUNCH,
                            c.punch().punchId(),
                            employeeId,
                            day,
                            c.punch().crossing().id(),
                            json(c.payload()),
                            c.status(),
                            c.punch().dueAt(),
                            c.punch().dueAt()));
                case OutboxReconciliation.Update u -> {
                    OutboxStore.LivePunch row = byCode.get(u.row().code());
                    Instant next =
                            u.status() == OutboxStatus.PENDING && u.row().status() != OutboxStatus.PENDING ? now : null;
                    store.update(row.id(), json(u.payload()), u.status(), next);
                }
                case OutboxReconciliation.VoidSent v -> {
                    OutboxStore.LivePunch row = byCode.get(v.row().code());
                    store.markVoided(row.id(), now);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("punchId", v.row().punchId());
                    payload.put("reason", v.reason());
                    OutboxStatus status =
                            features.hrmsEnabled() ? OutboxStatus.PENDING : OutboxStatus.HELD_NOT_CONNECTED;
                    store.insert(new OutboxStore.NewRow(
                            ids.next(CodeType.OUTBOX),
                            KIND_VOID,
                            v.row().punchId(),
                            employeeId,
                            day,
                            row.crossingExists() ? row.crossingId() : null,
                            json.writeValueAsString(payload),
                            status,
                            now,
                            now));
                }
                case OutboxReconciliation.Delete d ->
                    store.delete(byCode.get(d.row().code()).id());
            }
        }
        if (blocked && person != null) {
            notifications.publish(
                    "NR-07",
                    new NotificationRequest(
                            "1 employee missing an HRMS ID",
                            person.displayName() + " — their punches are blocked.",
                            "/employees?filter=missing-id",
                            "NR-07:" + employeeId + ":" + day,
                            Map.of("employee", person.displayName(), "day", day.toString())));
        }
        return plan.size();
    }

    /** Whether a punch of the day was sent to HRMS already (the correction dialog warns about re-sending). */
    @Transactional(readOnly = true)
    public boolean hasSentPunches(UUID employeeId, LocalDate day) {
        return store.hasSent(employeeId, day);
    }

    /** The payload JSON of R8, with the constant {@code final: true}. */
    String json(PunchPayload payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("punchId", payload.punchId());
        body.put("employeeId", payload.employeeId());
        body.put("timestamp", payload.timestamp());
        body.put("direction", payload.direction().name());
        body.put("type", payload.type().name());
        body.put("method", payload.method() == null ? null : payload.method().name());
        body.put("deviceId", payload.deviceId());
        body.put("eventId", payload.eventId());
        body.put("final", true);
        return json.writeValueAsString(body);
    }

    private PunchPayload payload(String stored) {
        JsonNode node = json.readTree(stored);
        return new PunchPayload(
                text(node, "punchId"),
                text(node, "employeeId"),
                text(node, "timestamp"),
                Direction.valueOf(node.path("direction").asString()),
                PunchType.valueOf(node.path("type").asString()),
                text(node, "method") == null ? null : CrossingMethod.valueOf(text(node, "method")),
                text(node, "deviceId"),
                text(node, "eventId"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }
}
