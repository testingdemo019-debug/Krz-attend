package com.krztechnologies.attend.rules.service;

import com.krztechnologies.attend.rules.service.PunchRulesEngine.DesiredPunch;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.PunchPayload;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * R8 outbox reconciliation as a pure plan: compares the punches HRMS should have for an (employee, shift day) with the
 * live outbox rows and says what to create, update, void or delete. The HRMS module applies the plan (the prototype's
 * {@code reconcileEmployeeDay} and {@code voidReasonFor}).
 */
public final class OutboxReconciliation {

    private OutboxReconciliation() {}

    /**
     * A live PUNCH row of the outbox (status not {@code VOIDED}).
     *
     * @param code OB-000412
     * @param crossing the crossing behind the punch as it is now, or null when it no longer exists
     */
    public record ExistingPunch(
            String code, String punchId, OutboxStatus status, PunchPayload payload, CrossingState crossing) {}

    /**
     * What a void reason needs to know about a crossing now.
     *
     * @param employeeId the identified employee, or null
     * @param voidReason the reason of an active void correction, or null
     * @param flipReason the reason of an active direction flip, or null
     */
    public record CrossingState(UUID employeeId, String voidReason, String flipReason) {}

    /** One step of the plan. */
    public sealed interface Change permits Create, Update, VoidSent, Delete {

        /** The punch becomes {@code BLOCKED_NO_EMPLOYEE_ID} with this change (NR-07). */
        default boolean blocks() {
            return false;
        }
    }

    /** A new desired punch: insert a row with this status, due at {@code punch.dueAt()}. */
    public record Create(DesiredPunch punch, PunchPayload payload, OutboxStatus status) implements Change {
        @Override
        public boolean blocks() {
            return status == OutboxStatus.BLOCKED_NO_EMPLOYEE_ID;
        }
    }

    /**
     * A row that is not sent yet gets a new payload and/or status: a blocked punch is released once the employee has
     * an HRMS Employee ID (send it now when it becomes {@code PENDING}); a punch whose employee lost the ID is blocked.
     */
    public record Update(ExistingPunch row, PunchPayload payload, OutboxStatus status) implements Change {
        @Override
        public boolean blocks() {
            return status == OutboxStatus.BLOCKED_NO_EMPLOYEE_ID && row.status() != status;
        }
    }

    /** A sent punch that is no longer desired: mark it {@code VOIDED} and enqueue a {@code VOID} entry. */
    public record VoidSent(ExistingPunch row, String reason) implements Change {}

    /** A row that is not sent and no longer desired: delete it. */
    public record Delete(ExistingPunch row) implements Change {}

    /**
     * The changes that bring the outbox of one (employee, shift day) in line with the desired punches.
     *
     * @param employeeId the employee the rows belong to
     * @param hrmsEmployeeId the employee's HRMS Employee ID, or null
     * @param hrmsEnabled {@code HRMS_ENABLED}
     * @param existing the live PUNCH rows of the employee and day
     */
    public static List<Change> plan(
            UUID employeeId,
            String hrmsEmployeeId,
            boolean hrmsEnabled,
            ZoneId zone,
            List<DesiredPunch> desired,
            List<ExistingPunch> existing) {
        List<Change> changes = new ArrayList<>();
        boolean hasId = hrmsEmployeeId != null;
        for (DesiredPunch punch : desired) {
            PunchPayload payload = punch.payload(hrmsEmployeeId, zone);
            ExistingPunch row = existing.stream()
                    .filter(e -> e.punchId().equals(punch.punchId()))
                    .findFirst()
                    .orElse(null);
            if (row == null) {
                changes.add(new Create(punch, payload, newStatus(hasId, hrmsEnabled)));
            } else if (row.status() != OutboxStatus.SENT) {
                OutboxStatus status = !hasId || row.status() == OutboxStatus.BLOCKED_NO_EMPLOYEE_ID
                        ? newStatus(hasId, hrmsEnabled)
                        : row.status();
                if (status != row.status() || !row.payload().equals(payload)) {
                    changes.add(new Update(row, payload, status));
                }
            }
        }
        Set<String> wanted = desired.stream().map(DesiredPunch::punchId).collect(Collectors.toSet());
        for (ExistingPunch row : existing) {
            if (wanted.contains(row.punchId())) {
                continue;
            }
            changes.add(
                    row.status() == OutboxStatus.SENT
                            ? new VoidSent(row, voidReason(row.crossing(), employeeId))
                            : new Delete(row));
        }
        return List.copyOf(changes);
    }

    /** The status of a new punch: blocked without an HRMS Employee ID, held while HRMS is disabled, else pending. */
    public static OutboxStatus newStatus(boolean hasEmployeeId, boolean hrmsEnabled) {
        if (!hasEmployeeId) {
            return OutboxStatus.BLOCKED_NO_EMPLOYEE_ID;
        }
        return hrmsEnabled ? OutboxStatus.PENDING : OutboxStatus.HELD_NOT_CONNECTED;
    }

    /** Why a sent punch is voided in HRMS (the prototype's {@code voidReasonFor}). */
    public static String voidReason(CrossingState crossing, UUID employeeId) {
        if (crossing == null) {
            return "Crossing removed";
        }
        if (crossing.voidReason() != null) {
            return "Voided in KRZ Attend: " + crossing.voidReason();
        }
        if (crossing.flipReason() != null) {
            return "Direction corrected in KRZ Attend: " + crossing.flipReason();
        }
        if (!employeeId.equals(crossing.employeeId())) {
            return "Identity removed (review undone or reassigned)";
        }
        return "Punch rules re-evaluated after a correction";
    }
}
