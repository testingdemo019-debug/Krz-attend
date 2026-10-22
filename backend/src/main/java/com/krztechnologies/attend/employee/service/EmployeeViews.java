package com.krztechnologies.attend.employee.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read models of Employees (the list and the profile). Work e-mails, consent details and template thumbnails are only
 * filled for viewers with {@code people.view.sensitive}; attendance days only with {@code attendance.view.all}.
 */
public final class EmployeeViews {

    private EmployeeViews() {}

    /** A department or location by code and name. */
    public record Ref(String code, String name) {}

    /** A person by id and name. */
    public record PersonRef(UUID id, String name) {}

    /** A filter value with its label and how many employees have it. */
    public record Option(String value, String label, int count) {}

    /** The list's filter options with their counts over every employee (the prototype's count column). */
    public record Facets(
            List<Option> department,
            List<Option> location,
            List<Option> type,
            List<Option> face,
            List<Option> tracking,
            List<Option> shift,
            List<Option> employeeId,
            List<Option> account) {}

    /**
     * The shift an employee works today (R1) and where it comes from.
     *
     * @param timing "09:30–18:30" or "21:00–06:00 (+1 day)"
     * @param source "organisation default", "from Support department since 1 Sep 2026", …
     */
    public record ShiftToday(String code, String name, String colour, String timing, String source) {}

    /**
     * One row of Employees.
     *
     * @param email work e-mail; null without people.view.sensitive
     * @param employeeId HRMS Employee ID, null when missing
     * @param employmentMissing no employment record, or no designation, location or join date ("Employment details
     *     missing")
     */
    public record ListItem(
            UUID id,
            String name,
            @Nullable String email,
            @Nullable String employeeId,
            @Nullable String designation,
            @Nullable Ref department,
            @Nullable Ref location,
            @Nullable String employmentType,
            String faceStatus,
            String consentStatus,
            String trackingMode,
            @Nullable String trackingReason,
            String trackingLabel,
            boolean accountEnabled,
            @Nullable LocalDate joinDate,
            @Nullable ShiftToday shiftToday,
            boolean employmentMissing) {}

    /**
     * Identity from Microsoft Entra ID (read-only here).
     *
     * @param email null without people.view.sensitive (except one's own)
     * @param hasAccess the person has a role in KRZ Attend ("Never" signed in vs "No access to KRZ Attend")
     */
    public record Identity(
            String displayName,
            @Nullable String email,
            UUID objectId,
            UUID tenantId,
            boolean accountEnabled,
            @Nullable String disabledReason,
            @Nullable LocalDate leftOn,
            @Nullable Instant lastSignInAt,
            @Nullable Instant syncedAt,
            boolean hasAccess) {}

    /**
     * Employment details as edited in KRZ Attend.
     *
     * @param version send it back when saving (0 when there is no record yet)
     */
    public record Employment(
            @Nullable String employeeId,
            @Nullable Ref department,
            @Nullable String designation,
            @Nullable String employmentType,
            @Nullable Ref location,
            @Nullable PersonRef manager,
            @Nullable LocalDate joinDate,
            @Nullable LocalDate endDate,
            String trackingMode,
            @Nullable String trackingReason,
            String trackingLabel,
            boolean missing,
            int version) {}

    /** A face template; {@code thumbnailUrl} only with people.view.sensitive. */
    /**
     * An active face template.
     *
     * @param source LIVE, UPLOAD, REVIEW, CAMERA (capture at an entry point) or CROSSING (from a crossing's crop)
     * @param light DAY, EVENING or NIGHT_IR for a template from a capture at an entry point, else null
     */
    public record Template(
            long id,
            @Nullable String pose,
            @Nullable Integer quality,
            String source,
            @Nullable String light,
            Instant createdAt,
            @Nullable String thumbnailUrl) {}

    /** The face profile. */
    public record Face(
            String status,
            @Nullable String reason,
            @Nullable LocalDate scheduledErasureOn,
            @Nullable Instant enrolledAt,
            List<Template> templates) {}

    /**
     * The consent record: the status for everyone, the rest only with people.view.sensitive.
     *
     * @param documentUrl the signed form, if one was uploaded
     * @param documentName the name it downloads as ("consent-EMP-1002.pdf")
     */
    public record Consent(
            String status,
            @Nullable String method,
            @Nullable Integer noticeVersion,
            @Nullable String recordedBy,
            @Nullable Instant recordedAt,
            @Nullable LocalDate givenOn,
            @Nullable LocalDate withdrawnOn,
            @Nullable String documentUrl,
            @Nullable String documentName) {}

    /** One day of "Last 14 days". */
    public record Day(
            LocalDate day,
            String shiftCode,
            String shiftName,
            String status,
            String statusCode,
            @Nullable Instant checkIn,
            @Nullable Instant checkOut,
            @Nullable Integer workedMinutes,
            boolean late,
            boolean finalised,
            boolean checkOutFinal,
            boolean missingCheckOut) {}

    /** One entry of the Activity tab. */
    public record Activity(
            Instant at,
            String action,
            String by,
            @Nullable String before,
            @Nullable String after,
            @Nullable String detail) {}

    /**
     * The employee profile.
     *
     * @param lastDays null without attendance.view.all
     */
    public record Profile(
            UUID id,
            Identity identity,
            Employment employment,
            @Nullable ShiftToday shiftToday,
            Face face,
            Consent consent,
            @Nullable List<Day> lastDays,
            List<Activity> activity) {}

    /** The outcome of a bulk action. */
    public record BulkResult(int changed, List<Skipped> skipped) {}

    /** A person a bulk action left out, and why (the prototype's "will be skipped"). */
    public record Skipped(UUID id, String name, String reason) {}
}
