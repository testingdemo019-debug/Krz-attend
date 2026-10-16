package com.krztechnologies.attend.organisation.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read models of the organisation pages (Locations, Departments, Shifts, Shift allocation, Holidays), returned to the
 * API as they are. Status values are {@code ACTIVE}/{@code ARCHIVED} (the UI labels them Active/Inactive); days are
 * {@code yyyy-mm-dd}; times are {@code HH:mm}.
 */
public final class OrganisationViews {

    private OrganisationViews() {}

    /** A person as a short reference (a department head: the prototype's person cell with the designation). */
    public record PersonRef(
            UUID id, String displayName, @Nullable String designation) {}

    /** A shift as a short reference (name and swatch colour). */
    public record ShiftRef(String code, String name, String colour) {}

    /** A location as a short reference. */
    public record LocationRef(String code, String name) {}

    // ---------------------------------------------------------------------------------------------------------------
    // Locations

    /**
     * An entry point of a location.
     *
     * @param cameras cameras mounted here; {@code enabledCameras} of them are enabled
     */
    public record EntryPointView(
            String code, String name, String status, int cameras, int enabledCameras, int version) {}

    /**
     * A location (list row and detail).
     *
     * @param hasCamera at least one enabled camera at one of its entry points (R5)
     * @param employees enabled employees whose location this is
     */
    public record LocationView(
            String code,
            String name,
            @Nullable String address,
            String status,
            String source,
            List<EntryPointView> entryPoints,
            int cameras,
            boolean hasCamera,
            long employees,
            Instant createdAt,
            int version) {}

    // ---------------------------------------------------------------------------------------------------------------
    // Departments

    /**
     * A department row.
     *
     * @param employees enabled members
     * @param shift the department's shift today: its active department allocation, else the organisation default
     * @param shiftSince start of that allocation; null = organisation default
     * @param locations names of the enabled members' locations
     */
    public record DepartmentRow(
            String code,
            String name,
            @Nullable String description,
            @Nullable PersonRef head,
            long employees,
            ShiftRef shift,
            @Nullable LocalDate shiftSince,
            List<String> locations,
            String status,
            String source,
            int version) {}

    /** A member of a department. */
    public record MemberView(
            UUID id,
            String displayName,
            @Nullable String employeeNumber,
            @Nullable String designation,
            @Nullable String location,
            boolean accountEnabled) {}

    /** A department allocation in the department drawer. */
    public record DepartmentAllocation(
            String code,
            ShiftRef shift,
            LocalDate from,
            @Nullable LocalDate until,
            String state) {}

    /**
     * The department drawer.
     *
     * @param deletable never used: no members and no allocations (otherwise it can only be deactivated)
     */
    public record DepartmentDetail(
            String code,
            String name,
            @Nullable String description,
            @Nullable PersonRef head,
            long employees,
            ShiftRef shift,
            @Nullable LocalDate shiftSince,
            List<String> locations,
            String status,
            String source,
            @Nullable String createdBy,
            Instant createdAt,
            List<DepartmentAllocation> allocations,
            List<MemberView> members,
            boolean deletable,
            int version) {}

    /**
     * People moved into a department.
     *
     * @param summary "2 from Sales, 1 from IT"
     */
    public record MoveResult(int moved, String summary) {}

    // ---------------------------------------------------------------------------------------------------------------
    // Shifts

    /** The rules of one shift version. */
    public record RulesView(
            LocalDate effectiveFrom,
            String start,
            String end,
            boolean overnight,
            String timing,
            int breakMinutes,
            List<Integer> weeklyOffs,
            String weeklyOffText,
            int lateGraceMinutes,
            int earlyGraceMinutes,
            BigDecimal halfDayBelowHours,
            BigDecimal absentBelowHours,
            BigDecimal windowBeforeHours,
            BigDecimal dayCloseHours) {}

    /**
     * A shift card.
     *
     * @param defaultShift the organisation default
     * @param today the version in effect today
     * @param scheduled the first version that starts after today, if any
     * @param employeesToday enabled employees who work this shift today
     * @param departmentAllocations active or scheduled department allocations of this shift
     * @param employeeAllocations active or scheduled individual allocations of this shift
     * @param createdAt when it was created (the prototype lists shifts to choose from in this order)
     */
    public record ShiftCard(
            String code,
            String name,
            String colour,
            String status,
            boolean defaultShift,
            String source,
            RulesView today,
            @Nullable RulesView scheduled,
            long employeesToday,
            long departmentAllocations,
            long employeeAllocations,
            Instant createdAt,
            int version) {}

    /**
     * A version in the shift drawer.
     *
     * @param state {@code CURRENT}, {@code SCHEDULED} or {@code PAST}
     */
    public record VersionView(
            RulesView rules,
            @Nullable String note,
            @Nullable String editedBy,
            Instant editedAt,
            String state) {}

    /**
     * The shift drawer.
     *
     * @param archiveBlockedReason why it can't be deactivated now, or null
     */
    public record ShiftDetail(
            ShiftCard shift,
            List<VersionView> versions,
            List<AllocationRow> allocations,
            @Nullable String archiveBlockedReason) {}

    /**
     * What saving a version from {@code effectiveFrom} recalculates (R10).
     *
     * @param confirmationRequired the day is today or earlier: the user must confirm the recalculation
     * @param days past days (including today) to recalculate; 0 for a future date
     * @param employees enabled employees on the shift on the first affected day
     * @param employeeDays employee-days to recalculate ("This recalculates N employee-days")
     */
    public record RecalculationImpact(
            LocalDate effectiveFrom, boolean confirmationRequired, int days, long employees, long employeeDays) {}

    // ---------------------------------------------------------------------------------------------------------------
    // Allocations

    /**
     * An allocation row.
     *
     * @param targetId department code or employee id
     * @param targetDetail "12 employees" for a department, the employee's department name for a person
     * @param timing the shift's hours on the later of {@code from} and today
     * @param state {@code ACTIVE}, {@code SCHEDULED}, {@code ENDED} or {@code CANCELLED}
     */
    public record AllocationRow(
            String code,
            String targetType,
            String targetId,
            String targetName,
            @Nullable String targetDetail,
            ShiftRef shift,
            String timing,
            LocalDate from,
            @Nullable LocalDate until,
            String state,
            @Nullable String note,
            @Nullable String createdBy,
            Instant createdAt) {}

    /**
     * An existing allocation the plan changes.
     *
     * @param change "ends on 31 Oct 2026" or "is cancelled"
     * @param reason {@code SAME_TARGET} or {@code INDIVIDUAL_REPLACED}
     */
    public record ConflictView(
            String code,
            String targetName,
            ShiftRef shift,
            LocalDate from,
            @Nullable LocalDate until,
            String change,
            String reason) {}

    /** One enabled employee's shift on the first day, before and after the plan. */
    public record ImpactRow(
            UUID employeeId,
            String displayName,
            ShiftRef before,
            ShiftRef after,
            boolean overridden,
            boolean changes) {}

    /**
     * The wizard's impact step (R9).
     *
     * @param individualCount members of the target departments with their own overlapping allocation
     * @param changing employees who change shift
     * @param unchanged employees already on the shift
     * @param overridden employees who keep their individual shift
     */
    public record PlanView(
            List<ConflictView> conflicts,
            List<ImpactRow> impact,
            int individualCount,
            long changing,
            long unchanged,
            long overridden) {}

    /**
     * A saved plan.
     *
     * @param created codes of the new allocations
     * @param adjusted existing allocations that were ended or cancelled
     */
    public record CommitResult(List<String> created, int adjusted, long changing) {}

    /** One recorded change of an allocation. */
    public record ChangeView(
            Instant at,
            String actor,
            String action,
            @Nullable String before,
            @Nullable String after) {}

    /** An allocation of a target with its changes. */
    public record HistoryEntry(AllocationRow allocation, List<ChangeView> changes) {}

    /**
     * "Who works which shift on a date".
     *
     * @param source {@code EMPLOYEE}, {@code DEPARTMENT} or {@code DEFAULT}
     * @param sourceText "individual allocation since 1 Sep 2026", "from Support department since …", "organisation
     *     default"
     * @param dayType {@code WORKING_DAY}, {@code WEEKLY_OFF} or {@code HOLIDAY}
     * @param holiday the holiday's name when {@code dayType} is HOLIDAY
     */
    public record LookupRow(
            UUID employeeId,
            String displayName,
            @Nullable String employeeNumber,
            @Nullable String department,
            ShiftRef shift,
            String timing,
            String source,
            String sourceText,
            @Nullable LocalDate versionFrom,
            String dayType,
            @Nullable String holiday) {}

    // ---------------------------------------------------------------------------------------------------------------
    // Holidays

    /**
     * A holiday.
     *
     * @param weekday "Thursday"
     * @param location null = all locations
     * @param sample loaded as an example; check before go-live
     */
    public record HolidayView(
            String code,
            LocalDate date,
            String weekday,
            String name,
            String type,
            @Nullable LocationRef location,
            String source,
            boolean sample,
            int version) {}

    /**
     * One row of a holiday CSV.
     *
     * @param result "Ready", "Skipped — already exists" or the error
     */
    public record ImportRow(
            int line,
            @Nullable LocalDate date,
            @Nullable String name,
            @Nullable String type,
            @Nullable LocationRef location,
            @Nullable String error,
            boolean duplicate,
            String result) {}

    /** The import preview: every row and the counts of the three chips. */
    public record ImportPreview(List<ImportRow> rows, long ready, long duplicates, long errors) {}

    /** A finished import. */
    public record ImportResult(int imported, int skipped, List<String> codes) {}

    // ---------------------------------------------------------------------------------------------------------------
    // Filter counts
    // ---------------------------------------------------------------------------------------------------------------

    /** A filter value with its label and how many rows of the list have it (the prototype's count column). */
    public record Option(String value, String label, int count) {}

    /** Departments and locations: by status ({@code ACTIVE}, {@code ARCHIVED}). */
    public record StatusFacets(List<Option> status) {}

    /** Shift allocations: by state, by what they apply to ({@code DEPARTMENT}, {@code EMPLOYEE}) and by shift. */
    public record AllocationFacets(List<Option> state, List<Option> type, List<Option> shift) {}

    /** The holidays of a year: by type and by location ({@code ALL} for every location). */
    public record HolidayFacets(List<Option> type, List<Option> location) {}
}
