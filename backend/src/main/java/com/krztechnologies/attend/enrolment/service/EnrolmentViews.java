package com.krztechnologies.attend.enrolment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the enrolment wizard. Embeddings and images never appear in them. */
public final class EnrolmentViews {

    private EnrolmentViews() {}

    /**
     * @param employeeId HRMS Employee ID, null when missing
     * @param location the employee's location, null when not set
     * @param locationHasCamera an enabled camera stands at one of the location's entry points (R5)
     */
    public record PersonRef(
            UUID id,
            String name,
            @Nullable String employeeId,
            @Nullable String department,
            @Nullable String designation,
            @Nullable String location,
            boolean locationHasCamera) {}

    /**
     * Someone waiting for enrolment, or found by the wizard's search.
     *
     * @param tracked tracking mode TRACKED (their arrivals appear as unknown faces until enrolled)
     * @param lockedUntil while enrolment is locked after liveness failures
     */
    public record QueueRow(
            PersonRef employee,
            String faceStatus,
            boolean tracked,
            @Nullable Instant lockedUntil,
            boolean accountEnabled) {}

    /**
     * The enrolment queue.
     *
     * @param cameraLocations the locations with an enabled camera, by name (where enrolled people are tracked)
     */
    public record Queue(List<QueueRow> people, List<String> cameraLocations) {}

    /** An active face template; {@code thumbnailUrl} only with people.view.sensitive. */
    public record TemplateRef(
            long id,
            @Nullable String pose,
            @Nullable Integer quality,
            @Nullable String thumbnailUrl) {}

    /**
     * The person chosen in the first step (the prototype's "Selected" panel).
     *
     * @param trackingLabel "Tracked by camera" or "Not tracked — …"
     * @param livenessFailures liveness failures within the lock window
     */
    public record Candidate(
            PersonRef employee,
            boolean accountEnabled,
            String faceStatus,
            Consent consent,
            String trackingLabel,
            List<TemplateRef> templates,
            @Nullable Instant lockedUntil,
            int livenessFailures) {}

    /** The worker's measurement of a capture (0–100 scores, degrees, pixels). */
    public record Quality(
            @Nullable Integer eyePx,
            @Nullable Double yaw,
            @Nullable Double pitch,
            @Nullable Integer sharp,
            @Nullable Integer brightness,
            @Nullable Integer score) {}

    /**
     * @param pose null for uploaded photos
     * @param rejectReason the prototype's texts, joined with "; "
     * @param livenessPassed null for uploads
     */
    public record Capture(
            long id,
            @Nullable String pose,
            boolean accepted,
            @Nullable String rejectReason,
            @Nullable Quality quality,
            @Nullable Boolean livenessPassed,
            Instant createdAt) {}

    /**
     * The employee's current consent.
     *
     * @param onFile GIVEN for the active notice version (the consent step may be skipped)
     */
    public record Consent(
            String status,
            @Nullable String method,
            @Nullable Integer noticeVersion,
            @Nullable LocalDate givenOn,
            @Nullable String recordedBy,
            boolean onFile) {}

    /**
     * An enrolment session.
     *
     * @param templates the employee's active templates now
     * @param activeNotice the notice version consent is given against
     * @param livenessFailures liveness failures within the lock window
     * @param goodCaptures accepted captures so far
     */
    public record Session(
            String code,
            PersonRef employee,
            String method,
            String status,
            Instant expiresAt,
            String faceStatus,
            int templates,
            Consent consent,
            int activeNotice,
            @Nullable Instant lockedUntil,
            int livenessFailures,
            int minGood,
            int goodCaptures,
            List<Capture> captures) {}

    /**
     * The outcome of one capture.
     *
     * @param failedAttempts liveness failures in the lock window, when this capture failed liveness
     * @param lockedUntil when this failure locked enrolment
     */
    public record CaptureResult(
            Capture capture,
            int goodCaptures,
            @Nullable Integer failedAttempts,
            @Nullable Instant lockedUntil) {}

    /**
     * @param confidence the band of the similarity
     * @param score null without settings.recognition
     * @param thumbnailUrl one of their template thumbnails, only with people.view.sensitive
     */
    public record Lookalike(
            UUID employeeId,
            String name,
            @Nullable String employeeIdNumber,
            @Nullable String department,
            String confidence,
            @Nullable BigDecimal score,
            @Nullable String thumbnailUrl) {}

    /** @param threshold Settings → Enrolment → duplicate check similarity */
    public record DuplicateCheck(double threshold, List<Lookalike> lookalikes) {}

    /**
     * A saved enrolment.
     *
     * @param templates templates added now
     * @param total active templates now
     * @param trackingChanged tracking switched to Tracked by camera
     */
    public record Completed(int templates, int total, boolean trackingChanged) {}

    /**
     * Test recognition: whom the gallery finds in one camera frame.
     *
     * @param faces faces in the frame
     * @param matched the best match is the enrolled employee, at or above the auto-accept similarity
     * @param name the best match at or above the review band, if any
     * @param confidence its band (HIGH, MEDIUM or LOW)
     */
    public record TestResult(
            int faces,
            boolean matched,
            @Nullable String name,
            @Nullable String confidence) {}
}
