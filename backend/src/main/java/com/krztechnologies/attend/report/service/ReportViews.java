package com.krztechnologies.attend.report.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the two reports. Percentages are left to the page: it gets the counts behind them. */
public final class ReportViews {

    private ReportViews() {}

    /**
     * Enrolment coverage. Groups: TRACKED, REENROL, PENDING, NO_FACE, NO_CAMERA, EXEMPT (the prototype's segments).
     *
     * @param eligible enabled employees at a location with a camera who are not exempt
     * @param covered those of them tracked by the camera
     * @param groups enabled employees per group, every group present
     * @param cameraLocations the locations with an enabled camera (the eligible employees work there)
     * @param noCameraLocations where the employees without a camera work
     */
    public record Coverage(
            int eligible,
            int covered,
            Map<String, Integer> groups,
            List<String> cameraLocations,
            List<String> noCameraLocations,
            List<DepartmentCoverage> departments) {}

    /** An active department's employees per group (departments without employees are left out). */
    public record DepartmentCoverage(String code, String name, Map<String, Integer> groups) {}

    /**
     * An enabled employee the camera does not (fully) cover: a row of the coverage table.
     *
     * @param employeeId HRMS Employee ID, null when missing
     * @param faceReason why the face profile is in its status (e.g. erased on request)
     * @param group the coverage group (not TRACKED)
     * @param templates active face templates ("Re-enrol" when there are some, else "Enrol")
     */
    public record Uncovered(
            UUID id,
            String name,
            @Nullable String employeeId,
            @Nullable String department,
            @Nullable String location,
            String faceStatus,
            @Nullable String faceReason,
            String group,
            int templates) {}

    /** A filter value and how many rows have it. */
    public record Option(String value, String label, int count) {}

    /** The coverage table's filter options with their counts over every employee not covered. */
    public record CoverageFacets(List<Option> group, List<Option> department) {}

    /**
     * Recognition quality over a period.
     *
     * @param averageFaceScore the average automatic face match, null without settings.recognition
     * @param faceConfidence its band (HIGH/MEDIUM/LOW), null without matches
     * @param thresholdProfile the active profile, e.g. TP-3
     * @param days one row per day, newest first
     * @param reenrol up to 10 employees recognised weakly
     * @param decisions review items created in the period by outcome
     */
    public record Recognition(
            LocalDate from,
            LocalDate to,
            Totals totals,
            @Nullable BigDecimal averageFaceScore,
            @Nullable String faceConfidence,
            @Nullable String thresholdProfile,
            List<Day> days,
            List<WeakMatch> reenrol,
            List<Decision> decisions) {}

    /** The KPI counts of the period (U-turns left out, except liveness alerts). */
    public record Totals(
            int crossings,
            int ins,
            int outs,
            int identifiedAutomatically,
            int inByFace,
            int outByReid,
            int sentToReview,
            int resolvedInReview,
            int livenessAlerts) {}

    /**
     * One day: the chart's segments ({@code faceAuto}, {@code reidAuto}, {@code review}, {@code notIdentified}) and the
     * table's columns.
     *
     * @param autoDecided face or Re-ID without review
     * @param reviewed crossings that went to review
     * @param outs and {@code reidOuts}: the Re-ID share of OUT
     * @param uptimePct the first camera's uptime, null when unknown
     * @param reviewWaitMinutes the average time to a person's decision, null without any
     */
    public record Day(
            LocalDate day,
            int crossings,
            int faceAuto,
            int reidAuto,
            int review,
            int notIdentified,
            int autoDecided,
            int reviewed,
            int outs,
            int reidOuts,
            int unknownFaces,
            int livenessAlerts,
            int duplicatesIgnored,
            @Nullable BigDecimal uptimePct,
            @Nullable Double reviewWaitMinutes) {}

    /** @param averageScore null without settings.recognition; {@code confidence} is its band */
    public record WeakMatch(
            UUID id,
            String name,
            @Nullable String employeeId,
            String faceStatus,
            @Nullable BigDecimal averageScore,
            @Nullable String confidence,
            int reviews) {}

    /** @param outcome a review action (CONFIRM, ASSIGN, …) or EXPIRED */
    public record Decision(String outcome, String label, int count) {}
}
