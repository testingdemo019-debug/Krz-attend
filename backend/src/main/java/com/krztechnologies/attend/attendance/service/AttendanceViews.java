package com.krztechnologies.attend.attendance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of Today, the evacuation list, the attendance log and the day drawer. */
public final class AttendanceViews {

    private AttendanceViews() {}

    public record Ref(String code, String name) {}

    /** A filter value with its label and how many employees have it. */
    public record Option(String value, String label, int count) {}

    /** Today's filter options with their counts among the rows of the chosen quick filter. */
    public record TodayFacets(
            List<Option> department,
            List<Option> shift,
            List<Option> status,
            List<Option> location,
            List<Option> type) {}

    /** The log's filter options with their counts among the employees of the range. */
    public record LogFacets(List<Option> department, List<Option> shift, List<Option> status) {}

    /** @param employeeId HRMS Employee ID, null when missing */
    public record PersonRef(
            UUID id,
            String name,
            @Nullable String employeeId,
            @Nullable Ref department,
            @Nullable Ref location,
            @Nullable String employmentType) {}

    /** @param timing "09:30–18:30" or "21:00–06:00 (+1 day)" */
    public record ShiftRef(String code, String name, String colour, String timing) {}

    /**
     * One row of Today: the employee's current shift day.
     *
     * @param now {@code INSIDE}, {@code OUTSIDE}, or null (not tracked, or no counted crossing yet)
     * @param hrmsStatus the outbox status of the check-out punch, else the check-in's; null without a check-in
     */
    public record TodayRow(
            PersonRef employee,
            LocalDate day,
            ShiftRef shift,
            String status,
            String statusCode,
            boolean finalised,
            boolean late,
            @Nullable Integer lateMinutes,
            boolean earlyExit,
            boolean missingCheckOut,
            @Nullable Instant checkIn,
            @Nullable String checkInMethod,
            @Nullable Instant lastMovement,
            @Nullable String lastDirection,
            @Nullable Instant checkOut,
            boolean checkOutFinal,
            @Nullable String now,
            @Nullable Integer workedMinutes,
            @Nullable String hrmsStatus,
            boolean tracked,
            String trackingLabel,
            boolean holiday,
            boolean weeklyOff) {}

    /**
     * The KPI tiles and the quick-filter counts of Today (the prototype's {@code todayStats}).
     *
     * @param quick the count of every quick filter, by key
     */
    public record TodayStats(
            int tracked,
            int notTracked,
            int inside,
            int checkedIn,
            int late,
            int notYetIn,
            int absent,
            int checkedOut,
            int earlyExit,
            int steppedOut,
            int missingCheckOut,
            int missingCheckIn,
            int working,
            int offToday,
            Map<String, Integer> quick) {}

    /** @param lastIn the last counted IN */
    public record EvacuationRow(
            int number,
            String name,
            @Nullable String employeeId,
            @Nullable String department,
            Instant lastIn) {}

    /**
     * The evacuation list: who is inside according to the cameras.
     *
     * @param unidentifiedIns unidentified INs today still waiting in the Review Queue (not on the list)
     */
    public record Evacuation(
            String organisation,
            List<String> cameras,
            Instant generatedAt,
            String generatedBy,
            int unidentifiedIns,
            List<EvacuationRow> people) {}

    /** One cell of the attendance log; {@code status} is null when the day does not apply. */
    public record LogCell(
            LocalDate day,
            @Nullable String status,
            @Nullable String statusCode,
            boolean late,
            boolean finalised,
            @Nullable Instant checkIn,
            @Nullable Instant checkOut) {}

    public record LogTotals(int present, int late, int absent, int missing, int workedMinutes) {}

    public record LogRow(PersonRef employee, List<LogCell> days, LogTotals totals) {}

    /** A line of the daily-detail export. */
    public record DailyRow(
            PersonRef employee,
            LocalDate day,
            String shiftName,
            String status,
            @Nullable Instant checkIn,
            @Nullable Instant checkOut,
            @Nullable Integer workedMinutes,
            boolean late,
            boolean earlyExit,
            boolean finalised) {}

    /** A time of the shift day and what happens there. */
    public record Marker(Instant at, String label) {}

    /**
     * A crossing of the day with its rule outcome.
     *
     * @param flippedFrom the direction before a flip correction, or null
     */
    public record DayCrossing(
            String code,
            Instant at,
            String direction,
            @Nullable String flippedFrom,
            @Nullable String method,
            @Nullable String effect,
            @Nullable String reason,
            boolean voided) {}

    /** An outbox row of the day; {@code type} and {@code at} are null for a VOID entry. */
    public record DayPunch(
            String punchId,
            String kind,
            @Nullable String type,
            @Nullable Instant at,
            String status) {}

    /** The times of the shift day (R2). */
    public record Times(Instant windowStart, Instant start, Instant end, Instant cutoff, Instant close) {}

    /**
     * The day drawer, evaluated now with the same engine (nothing stored).
     *
     * @param workedHours {@code SPAN} (first IN to last OUT) or {@code INSIDE} (time inside)
     * @param shiftSource "individual allocation since …", "from … department since …" or "organisation default"
     */
    public record DayDetail(
            PersonRef employee,
            LocalDate day,
            String status,
            String statusCode,
            boolean finalised,
            boolean late,
            @Nullable Integer lateMinutes,
            @Nullable Instant checkIn,
            @Nullable Instant checkOut,
            boolean checkOutFinal,
            boolean missingCheckOut,
            @Nullable Integer workedMinutes,
            String workedHours,
            ShiftRef shift,
            String shiftSource,
            @Nullable String holiday,
            boolean weeklyOff,
            boolean tracked,
            String trackingLabel,
            Times times,
            List<Marker> markers,
            List<DayCrossing> crossings,
            List<DayPunch> punches,
            Instant evaluatedAt) {}
}
