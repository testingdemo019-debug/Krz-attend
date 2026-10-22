package com.krztechnologies.attend.crossing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Events page. Match scores are only filled for viewers with settings.recognition. */
public final class CrossingViews {

    private CrossingViews() {}

    public record CameraRef(String code, String name) {}

    /** @param employeeId HRMS Employee ID, null when missing */
    public record PersonRef(UUID id, String name, @Nullable String employeeId) {}

    /**
     * One crossing of the list.
     *
     * @param flippedFrom the direction before a flip correction, or null
     * @param person the identified employee, or null (then {@code unknownLabel} or "Unknown face")
     * @param confidence {@code HIGH}/{@code MEDIUM}/{@code LOW} of the face (or Re-ID) score, or null
     * @param score the raw score; null without settings.recognition
     * @param faceCrop a face crop was stored (served by the media endpoint, blurred by default)
     */
    public record EventRow(
            String code,
            Instant occurredAt,
            CameraRef camera,
            String direction,
            @Nullable String flippedFrom,
            boolean uTurn,
            @Nullable PersonRef person,
            @Nullable String unknownLabel,
            @Nullable String method,
            @Nullable String confidence,
            @Nullable BigDecimal score,
            String decision,
            @Nullable String liveness,
            @Nullable String effect,
            @Nullable String effectReason,
            boolean faceCrop,
            boolean bodyCrop,
            boolean cropsPurged) {}

    /** Identified crossings (no U-turns, not voided) of one IST day and hour in one direction. */
    public record HourCount(LocalDate day, int hour, String direction, int count) {}

    /** An active correction: who made it and why. */
    public record Correction(String by, String reason) {}

    public record ReviewRef(String code, String status) {}

    /**
     * The attendance day of an identified crossing.
     *
     * @param shiftTiming "09:30–18:30" or "21:00–06:00 (+1 day)"
     * @param status the day's status ({@code AttendanceStatus} name), null before it is computed
     * @param trackingLabel "Tracked by camera" or "Not tracked — …" (the employee's tracking now)
     */
    public record AttendanceRef(
            LocalDate shiftDay,
            String shiftCode,
            String shiftName,
            String shiftTiming,
            @Nullable String status,
            @Nullable String statusCode,
            boolean finalised,
            @Nullable String trackingLabel) {}

    /**
     * A face or body match of a crossing.
     *
     * @param confidence {@code HIGH}, {@code MEDIUM} or {@code LOW}
     * @param score null without settings.recognition
     */
    public record Match(String confidence, @Nullable BigDecimal score) {}

    /** A filter value with its label and count in the chosen days. */
    public record Option(String value, String label, int count) {}

    /**
     * The Events filters' options with their counts in the chosen days ({@code method} has {@code NONE} for crossings
     * nobody was identified for).
     */
    public record Facets(
            List<Option> direction,
            List<Option> method,
            List<Option> decision,
            List<Option> effect,
            List<Option> camera,
            List<Option> employee) {}

    /** An HRMS outbox row of the crossing's punch ({@code type} is CHECK_IN, CHECK_OUT, MOVEMENT; null for VOID). */
    public record PunchRow(
            String punchId,
            String kind,
            @Nullable String type,
            String status,
            @Nullable Instant lastAttemptAt) {}

    public record ChangeRow(
            Instant at,
            String action,
            String actor,
            @Nullable String after) {}

    /**
     * The crossing drawer.
     *
     * @param department the identified employee's department
     * @param quality the face quality chips, or null without a measured face
     * @param faceMatch the face match (identified or suggested), or null
     * @param bodyMatch the Re-ID body match, or null
     * @param matchedIn a Re-ID exit's matching IN that day ("matched to the IN at 09:12"), or null
     * @param flip an active direction flip, or null
     * @param voided an active void, or null
     * @param attendance null for a crossing without identity
     * @param template whether the face crop can become the employee's template (D-118); null without people.enrol,
     *     identity or face crop
     */
    public record EventDetail(
            EventRow event,
            String entryPoint,
            @Nullable String department,
            @Nullable FaceQuality quality,
            @Nullable Match faceMatch,
            @Nullable Match bodyMatch,
            @Nullable Instant matchedIn,
            @Nullable Integer trackId,
            @Nullable ReviewRef review,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            @Nullable Correction flip,
            @Nullable Correction voided,
            @Nullable AttendanceRef attendance,
            List<PunchRow> punches,
            List<ChangeRow> changes,
            @Nullable TemplateOffer template) {}

    /** Whether the crossing's face crop can become a face template now, with the reason when not. */
    public record TemplateOffer(boolean ok, String why) {}
}
