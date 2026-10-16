package com.krztechnologies.attend.privacy.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.settings.PrivacySettings;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.privacy.repository.RetentionQueries;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly retention run (R17, R12 unknown expiry, R16 scheduled erasures). Every step works in batches, one
 * transaction per batch, and is counted; a failing step is recorded and the others still run. The whole run is
 * summarised in ONE audit entry (category Privacy, actor System); each scheduled erasure is also audited on its own,
 * as in the prototype.
 */
@Service
public class RetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);

    static final int BATCH = 500;
    static final Duration NOTIFICATIONS_KEPT = Duration.ofDays(90);
    static final Duration ENROLMENT_SESSIONS_KEPT = Duration.ofDays(7);
    static final Duration DEACTIVATED_TEMPLATES_KEPT = Duration.ofDays(7);
    static final int CAMERA_HEALTH_DAYS = 400;
    static final String ERASURE_REASON = "Scheduled erasure after account was disabled";
    private static final Map<String, String> FACE_STATUS_LABELS = Map.of(
            "ENROLLED", "Enrolled",
            "NOT_ENROLLED", "Not enrolled",
            "NEEDS_REENROL", "Needs re-enrolment",
            "CONSENT_PENDING", "Consent pending",
            "DEACTIVATED", "Deactivated");

    private final RetentionQueries queries;
    private final MediaPurge media;
    private final BiometricErasureService erasure;
    private final SettingsService settings;
    private final AuditService audit;
    private final TimeService time;
    private final TransactionTemplate transactions;

    public RetentionService(
            RetentionQueries queries,
            MediaPurge media,
            BiometricErasureService erasure,
            SettingsService settings,
            AuditService audit,
            TimeService time,
            TransactionTemplate transactions) {
        this.queries = queries;
        this.media = media;
        this.erasure = erasure;
        this.settings = settings;
        this.audit = audit;
        this.time = time;
        this.transactions = transactions;
    }

    /**
     * What one run did.
     *
     * @param counts every step's counters, in run order (also the audit entry's details)
     * @param failedSteps steps that stopped with an error (they run again the next night)
     */
    public record RetentionSummary(Map<String, Long> counts, List<String> failedSteps) {}

    public RetentionSummary run() {
        Instant now = time.now();
        LocalDate today = time.dayOf(now);
        PrivacySettings privacy = settings.privacy();
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();

        step("crops", failed, () -> purgeCrops(now.minus(Duration.ofDays(privacy.cropDays())), now, counts));
        step(
                "unknown items",
                failed,
                () -> expireUnknownItems(now.minus(Duration.ofHours(privacy.unknownHours())), now, privacy, counts));
        step(
                "crossings",
                failed,
                () -> deleteCrossings(
                        now.minus(Duration.ofDays(privacy.eventDays())), today.minusDays(privacy.eventDays()), counts));
        step(
                "audit",
                failed,
                () -> add(
                        counts,
                        "auditEntriesPurged",
                        tx(() -> queries.purgeAudit(now.minus(Duration.ofDays(privacy.auditDays()))))));
        step("notifications", failed, () -> deleteNotifications(now.minus(NOTIFICATIONS_KEPT), counts));
        step("enrolment sessions", failed, () -> deleteEnrolmentSessions(now.minus(ENROLMENT_SESSIONS_KEPT), counts));
        step("templates", failed, () -> deleteDeactivatedTemplates(now.minus(DEACTIVATED_TEMPLATES_KEPT), counts));
        step(
                "camera health",
                failed,
                () -> add(counts, "cameraHealthRowsDeleted", tx(() -> (long) queries.deleteCameraHealthBefore(
                        today.minusDays(CAMERA_HEALTH_DAYS), now.minus(Duration.ofDays(CAMERA_HEALTH_DAYS))))));
        runErasures(today, counts, failed);

        Map<String, Object> details = new LinkedHashMap<>(counts);
        details.put("failedSteps", failed);
        transactions.executeWithoutResult(status -> audit.record(
                AuditActor.SYSTEM,
                AuditCategory.PRIVACY,
                "Retention run",
                "Retention",
                null,
                summaryText(counts, failed),
                details));
        LOG.info("Retention run finished: {}{}", counts, failed.isEmpty() ? "" : ", failed steps " + failed);
        return new RetentionSummary(Map.copyOf(counts), List.copyOf(failed));
    }

    /** Entrance crops older than cropDays: files deleted, crops_purged_at set. */
    private void purgeCrops(Instant cutoff, Instant now, Map<String, Long> counts) {
        long afterId = 0;
        while (true) {
            long from = afterId;
            Long last = tx(() -> {
                var batch = queries.cropsOlderThan(cutoff, from, BATCH);
                var result = media.deleteCrops(batch);
                add(counts, "cropCrossings", queries.markCropsPurged(result.purgedCrossings(), now));
                add(counts, "cropFiles", result.filesDeleted());
                add(counts, "cropFileFailures", result.failures());
                return batch.size() < BATCH ? null : batch.getLast().id();
            });
            if (last == null) {
                return;
            }
            afterId = last;
        }
    }

    /** R12: open unknown-face items whose last sighting is older than unknownHours expire; their crops go. */
    private void expireUnknownItems(Instant cutoff, Instant now, PrivacySettings privacy, Map<String, Long> counts) {
        String itemReason = "Not resolved within " + privacy.unknownHours() + " h";
        String effectReason = itemReason + " — crop purged";
        while (true) {
            Integer expired = tx(() -> {
                List<Long> items = queries.expireUnknownItems(cutoff, itemReason, BATCH);
                List<Long> crossings = queries.expireCrossingsOf(items, effectReason);
                var result = media.deleteCrops(queries.cropsOf(crossings));
                queries.markCropsPurged(result.purgedCrossings(), now);
                add(counts, "unknownItemsExpired", items.size());
                add(counts, "unknownCrossingsExpired", crossings.size());
                add(counts, "unknownCropFiles", result.filesDeleted());
                add(counts, "cropFileFailures", result.failures());
                return items.size();
            });
            if (expired < BATCH) {
                return;
            }
        }
    }

    /**
     * Crossings older than eventDays and everything that references them, in FK-safe order inside one transaction per
     * batch: HRMS outbox (any status) → attendance days → corrections → crossings → orphaned review items. Then the
     * attendance days and outbox rows of shift days before the cut-off that no crossing referenced (absences).
     */
    private void deleteCrossings(Instant cutoff, LocalDate cutoffDay, Map<String, Long> counts) {
        long afterId = 0;
        while (true) {
            long from = afterId;
            Long last = tx(() -> {
                List<Long> batch = queries.crossingsOlderThan(cutoff, from, BATCH);
                if (batch.isEmpty()) {
                    return null;
                }
                // Crops still on disk go first; a crossing whose file could not be deleted stays until the next run.
                var withCrops = queries.cropsOf(batch);
                var crops = media.deleteCrops(withCrops);
                add(counts, "cropFiles", crops.filesDeleted());
                add(counts, "cropFileFailures", crops.failures());
                Set<Long> kept = new HashSet<>();
                withCrops.forEach(c -> kept.add(c.id()));
                crops.purgedCrossings().forEach(kept::remove);
                List<Long> crossings =
                        batch.stream().filter(id -> !kept.contains(id)).toList();
                if (!crossings.isEmpty()) {
                    countOutbox(counts, queries.deleteOutboxOfCrossings(crossings));
                    add(counts, "attendanceDaysDeleted", queries.deleteAttendanceDaysOfCrossings(crossings));
                    add(counts, "correctionsDeleted", queries.deleteCorrectionsOfCrossings(crossings));
                    List<Long> reviewItems = queries.deleteCrossings(crossings);
                    add(counts, "crossingsDeleted", crossings.size());
                    add(counts, "reviewItemsDeleted", queries.deleteOrphanedReviewItems(reviewItems));
                }
                return batch.size() < BATCH ? null : batch.getLast();
            });
            if (last == null) {
                break;
            }
            afterId = last;
        }
        tx(() -> {
            countOutbox(counts, queries.deleteOutboxBefore(cutoffDay));
            add(counts, "attendanceDaysDeleted", queries.deleteAttendanceDaysBefore(cutoffDay));
            return null;
        });
    }

    private void deleteNotifications(Instant cutoff, Map<String, Long> counts) {
        tx(() -> {
            add(counts, "notificationsDeleted", queries.deleteNotificationsBefore(cutoff));
            add(counts, "emailsDeleted", queries.deleteEmailsBefore(cutoff));
            return null;
        });
    }

    /** Enrolment sessions finished more than 7 days ago, with their unsaved captures and capture images. */
    private void deleteEnrolmentSessions(Instant cutoff, Map<String, Long> counts) {
        tx(() -> {
            List<Long> sessions = queries.enrolmentSessionsBefore(cutoff);
            var images = media.deleteUploads(queries.captureImagesOfSessions(sessions));
            Set<Long> deletable = new HashSet<>(sessions);
            images.failedRows().forEach(deletable::remove);
            add(counts, "enrolmentSessionsDeleted", queries.deleteEnrolmentSessions(deletable));
            add(counts, "enrolmentImagesDeleted", images.filesDeleted());
            add(counts, "uploadFileFailures", images.failedRows().size());
            return null;
        });
    }

    /** Templates deactivated more than 7 days ago (re-enrolment), with their thumbnails. */
    private void deleteDeactivatedTemplates(Instant cutoff, Map<String, Long> counts) {
        tx(() -> {
            var thumbnails = media.deleteUploads(queries.templatesDeactivatedBefore(cutoff));
            add(counts, "templatesDeleted", queries.deleteTemplates(thumbnails.removedRows()));
            add(counts, "templateThumbnailsDeleted", thumbnails.filesDeleted());
            add(counts, "uploadFileFailures", thumbnails.failedRows().size());
            return null;
        });
    }

    /** R16: biometric data of accounts disabled eraseAfterDays ago; one transaction and one audit entry each. */
    private void runErasures(LocalDate today, Map<String, Long> counts, List<String> failed) {
        add(counts, "erasures", 0);
        List<UUID> due;
        try {
            due = tx(() -> queries.erasuresDue(today));
        } catch (RuntimeException e) {
            LOG.error("Retention step 'erasures' failed", e);
            failed.add("erasures");
            return;
        }
        for (UUID employeeId : due) {
            try {
                tx(() -> {
                    String before = queries.faceStatus(employeeId);
                    var result = erasure.erase(employeeId, ERASURE_REASON, AuditActor.SYSTEM);
                    audit.record(
                            AuditActor.SYSTEM,
                            AuditCategory.EMPLOYEES,
                            "Erased biometric data (scheduled)",
                            queries.displayName(employeeId),
                            FACE_STATUS_LABELS.getOrDefault(before, before),
                            "Erased",
                            Map.of(
                                    "employeeId", employeeId.toString(),
                                    "templates", result.templates(),
                                    "captures", result.captures(),
                                    "crossingsWithCrops", result.crossings(),
                                    "files", result.files()));
                    add(counts, "erasures", 1);
                    add(counts, "erasureFiles", result.files());
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.error("Scheduled erasure of employeeId={} failed", employeeId, e);
                if (!failed.contains("erasures")) {
                    failed.add("erasures");
                }
            }
        }
    }

    private static void countOutbox(Map<String, Long> counts, List<String> statuses) {
        add(counts, "hrmsOutboxDeleted", statuses.size());
        for (String status : statuses) {
            switch (status) {
                case "HELD_NOT_CONNECTED" -> add(counts, "hrmsOutboxHeld", 1);
                case "BLOCKED_NO_EMPLOYEE_ID" -> add(counts, "hrmsOutboxBlocked", 1);
                case "FAILED" -> add(counts, "hrmsOutboxFailed", 1);
                case "PENDING" -> add(counts, "hrmsOutboxPending", 1);
                default -> add(counts, "hrmsOutboxSentOrVoided", 1);
            }
        }
    }

    private static String summaryText(Map<String, Long> c, List<String> failed) {
        String text = String.join(
                " · ",
                "Crops: " + n(c, "cropCrossings") + " crossings, " + n(c, "cropFiles") + " files",
                "Unknown faces: " + n(c, "unknownItemsExpired") + " items expired (" + n(c, "unknownCrossingsExpired")
                        + " crossings, " + n(c, "unknownCropFiles") + " files)",
                "Crossings: " + n(c, "crossingsDeleted") + " deleted, with " + n(c, "attendanceDaysDeleted")
                        + " attendance days, " + n(c, "correctionsDeleted") + " corrections, "
                        + n(c, "reviewItemsDeleted") + " review items",
                "HRMS outbox: " + n(c, "hrmsOutboxDeleted") + " deleted (" + n(c, "hrmsOutboxHeld") + " held, "
                        + n(c, "hrmsOutboxBlocked") + " blocked, " + n(c, "hrmsOutboxFailed") + " failed, "
                        + n(c, "hrmsOutboxPending") + " pending)",
                "Audit: " + n(c, "auditEntriesPurged") + " entries purged",
                "Notifications: " + n(c, "notificationsDeleted") + ", e-mails: " + n(c, "emailsDeleted"),
                "Enrolment: " + n(c, "enrolmentSessionsDeleted") + " sessions, " + n(c, "enrolmentImagesDeleted")
                        + " images",
                "Deactivated templates: " + n(c, "templatesDeleted"),
                "Camera health: " + n(c, "cameraHealthRowsDeleted") + " rows",
                "Scheduled erasures: " + n(c, "erasures"));
        long fileFailures = n(c, "cropFileFailures") + n(c, "uploadFileFailures");
        if (fileFailures > 0) {
            text += " · Files not deleted (retried next run): " + fileFailures;
        }
        if (!failed.isEmpty()) {
            text += " · Failed steps: " + String.join(", ", failed);
        }
        return text;
    }

    private static long n(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }

    private static void add(Map<String, Long> counts, String key, long amount) {
        counts.merge(key, amount, Long::sum);
    }

    private <T> T tx(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private static void step(String name, List<String> failed, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.error("Retention step '{}' failed", name, e);
            failed.add(name);
        }
    }
}
