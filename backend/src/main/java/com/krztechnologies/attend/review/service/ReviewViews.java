package com.krztechnologies.attend.review.service;

import com.krztechnologies.attend.crossing.service.FaceQuality;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the Review Queue. Candidate scores are only filled for viewers with settings.recognition. */
public final class ReviewViews {

    private ReviewViews() {}

    /**
     * A suggested employee.
     *
     * @param confidence {@code HIGH}, {@code MEDIUM} or {@code LOW}
     * @param score null without settings.recognition
     */
    public record Candidate(
            UUID employeeId,
            String name,
            @Nullable String department,
            String confidence,
            @Nullable BigDecimal score) {}

    /**
     * A crossing of the item.
     *
     * @param quality the face quality chips, or null without a measured face
     * @param faceCrop a face crop is stored (served by the media endpoint, blurred by default)
     * @param bodyCrop a body crop is stored
     * @param liveness {@code PASSED}, {@code FAILED} or {@code SKIPPED}, or null
     * @param template whether this crossing's face crop may become a face template (consent is checked on the
     *     decision)
     */
    public record Sighting(
            String code,
            Instant at,
            String direction,
            String cameraCode,
            String cameraName,
            @Nullable FaceQuality quality,
            boolean faceCrop,
            boolean bodyCrop,
            boolean cropsPurged,
            @Nullable Integer trackId,
            @Nullable String liveness,
            Eligibility template) {}

    /** Whether the best crop may become a face template, and the prototype's text why (not). */
    public record Eligibility(boolean ok, String why) {}

    public record PersonRef(UUID id, String name) {}

    /** A back-view exit's top candidate came in through this crossing today ("Came in · 09:12"). */
    public record CameIn(String code, Instant at, boolean faceCrop) {}

    /**
     * How the item was resolved, or that it expired.
     *
     * @param action a decision ({@code CONFIRM} … {@code FALSE_ALARM}) or {@code EXPIRED}
     * @param label the prototype's label ("Confirmed", "Assigned to employee", …, "Expired – no action")
     * @param by the reviewer's name, or "System"
     * @param undoUntil the undo window's end, while it lasts
     */
    public record Resolution(
            String action,
            String label,
            @Nullable PersonRef employee,
            String by,
            Instant at,
            @Nullable String reason,
            @Nullable String note,
            boolean templateAdded,
            @Nullable Instant undoUntil) {}

    /**
     * A review item with what its card shows.
     *
     * @param typeLabel "Unknown face", "Uncertain match", "Back-view exit" or "Liveness alert"
     * @param label an unknown face's cluster label ("Unknown #A1"), else null
     * @param waiting the item has waited longer than the NR-03 threshold
     * @param direction the first crossing's direction
     * @param reason why the item needs a person: the uncertain match's or back-view exit's reason, the liveness
     *     alert's hint; null for unknown faces
     * @param cameIn a back-view exit's matching IN of the top candidate today, or null
     * @param bestSighting the crossing whose crop counts (the best one of an unknown cluster)
     * @param resolution null while open
     */
    public record Item(
            String code,
            String type,
            String typeLabel,
            String status,
            @Nullable String label,
            Instant createdAt,
            Instant lastSightingAt,
            boolean waiting,
            @Nullable String direction,
            List<Sighting> sightings,
            List<Candidate> candidates,
            @Nullable String reason,
            @Nullable CameIn cameIn,
            @Nullable String bestSighting,
            @Nullable Resolution resolution) {}

    /** Open items per type ({@code UNKNOWN}, {@code UNCERTAIN}, {@code REID}, {@code LIVENESS}) and resolved ones. */
    public record Counts(Map<String, Integer> open, int resolved) {}

    /**
     * Who resolved items, and how many (the Resolved tab's "Resolved by" filter).
     *
     * @param id null for the system
     */
    public record Resolver(@Nullable UUID id, String name, int count) {}

    /**
     * The Resolved tab's filter options with their counts over every item no longer open.
     *
     * @param type items per type
     * @param action items per decision, and {@code EXPIRED}
     */
    public record Facets(Map<String, Integer> type, Map<String, Integer> action, List<Resolver> resolvedBy) {}

    /**
     * The Dashboard's review numbers.
     *
     * @param open open items per type
     * @param unknownToday unknown faces first seen today
     * @param waiting open items older than {@code waitingMinutes} (NR-03's threshold)
     * @param oldestWaiting when the oldest of them came in
     */
    public record Glance(
            Map<String, Integer> open,
            int unknownToday,
            int waiting,
            @Nullable Instant oldestWaiting,
            int waitingMinutes) {}

    /** A punch a decision produced (the toast's "check-in 09:12"). */
    public record Effect(String effect, Instant at) {}

    /**
     * @param undoSeconds how long {@code undoToken} works
     * @param effects the check-ins, check-outs and movements of a single decision, in time order (none for bulk)
     */
    public record Applied(String undoToken, int undoSeconds, List<String> codes, List<Effect> effects) {}
}
