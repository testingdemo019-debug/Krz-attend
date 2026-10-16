package com.krztechnologies.attend.hrms.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Read models of Settings → KRZ HRMS. */
public final class HrmsViews {

    private HrmsViews() {}

    /** @param backoffSeconds wait before attempt n+1; the last value repeats */
    public record Retries(int maxAttempts, List<Integer> backoffSeconds) {}

    /**
     * Where a kind of master data comes from. Fetching it from KRZ HRMS is not available yet, so the switch is shown off
     * and disabled.
     *
     * @param fromHrmsLater KRZ HRMS will be able to supply it (else "Not applicable")
     */
    public record MasterData(String data, String sourceNow, boolean fromHrmsLater) {}

    /**
     * The page header: the connection, what is sent and how, the payload format and the outbox counts.
     *
     * @param enabled HRMS_ENABLED (releasing held punches needs it)
     * @param pushMode FINAL (check-in and final check-out) or EVERY (every counted crossing)
     * @param counts entries per outbox status, every status present
     */
    public record Overview(
            HrmsClient.Connection connection,
            boolean enabled,
            Retries retries,
            String pushMode,
            List<MasterData> masterData,
            JsonNode punchSample,
            JsonNode voidSample,
            Map<String, Long> counts) {}

    /** @param employeeId HRMS Employee ID, null when missing (the "Add ID" link) */
    public record EmployeeRef(
            UUID id, String name, @Nullable String employeeId) {}

    /**
     * An outbox entry.
     *
     * @param type CHECK_IN, CHECK_OUT or MOVEMENT for a punch; VOID for a void
     * @param punchAt the punch time (null for a void)
     * @param payload what is (or was) sent, for the drawer
     */
    public record Entry(
            String code,
            String kind,
            String punchId,
            String type,
            @Nullable Instant punchAt,
            @Nullable EmployeeRef employee,
            @Nullable LocalDate shiftDay,
            String status,
            String statusLabel,
            int attempts,
            @Nullable String lastError,
            Instant createdAt,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextAttemptAt,
            @Nullable Instant sentAt,
            JsonNode payload) {}

    /** @param entries how many entries were queued again */
    public record Requeued(int entries) {}
}
