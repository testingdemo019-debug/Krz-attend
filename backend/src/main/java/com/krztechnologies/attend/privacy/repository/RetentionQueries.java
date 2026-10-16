package com.krztechnologies.attend.privacy.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of retention (R17) and erasure over the tables of other modules (crossings, review, attendance, HRMS outbox,
 * enrolment, notifications, camera health). Native SQL with bind parameters only; rows that carry an optimistic
 * {@code version} get it bumped, so an edit that loaded the row before the purge fails instead of undoing it.
 */
@Repository
public class RetentionQueries {

    private final JdbcClient jdbc;

    public RetentionQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A crossing with its stored crop paths (relative to DATA_DIR/crops). */
    public record CrossingCrops(long id, String face, String faceBlur, String body, String bodyBlur) {

        public List<String> paths() {
            return Stream.of(face, faceBlur, body, bodyBlur)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    /** A stored file path (relative to DATA_DIR/uploads) of a row about to be deleted. */
    public record StoredFile(long id, String path) {}

    // ---- crops -------------------------------------------------------------------------------------------------

    /** Crossings older than the cut-off whose crops are still on disk, in ID order after {@code afterId}. */
    public List<CrossingCrops> cropsOlderThan(Instant cutoff, long afterId, int limit) {
        return jdbc.sql("""
                        SELECT id, face_crop_path, face_crop_blur_path, body_crop_path, body_crop_blur_path
                          FROM attend.crossing
                         WHERE occurred_at < :cutoff
                           AND id > :afterId
                           AND crops_purged_at IS NULL
                           AND (face_crop_path IS NOT NULL OR face_crop_blur_path IS NOT NULL
                                OR body_crop_path IS NOT NULL OR body_crop_blur_path IS NOT NULL)
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("afterId", afterId)
                .param("limit", limit)
                .query(RetentionQueries::crops)
                .list();
    }

    /** The given crossings' crops that are still on disk. */
    public List<CrossingCrops> cropsOf(Collection<Long> crossingIds) {
        if (crossingIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, face_crop_path, face_crop_blur_path, body_crop_path, body_crop_blur_path
                          FROM attend.crossing
                         WHERE id IN (:ids) AND crops_purged_at IS NULL
                        """)
                .param("ids", crossingIds)
                .query(RetentionQueries::crops)
                .list();
    }

    /** An employee's crossings whose crops are still on disk (erasure). */
    public List<CrossingCrops> cropsOfEmployee(UUID employeeId) {
        return jdbc.sql("""
                        SELECT id, face_crop_path, face_crop_blur_path, body_crop_path, body_crop_blur_path
                          FROM attend.crossing
                         WHERE employee_id = :employee AND crops_purged_at IS NULL
                           AND (face_crop_path IS NOT NULL OR face_crop_blur_path IS NOT NULL
                                OR body_crop_path IS NOT NULL OR body_crop_blur_path IS NOT NULL)
                        """)
                .param("employee", employeeId)
                .query(RetentionQueries::crops)
                .list();
    }

    public int markCropsPurged(Collection<Long> crossingIds, Instant at) {
        if (crossingIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE attend.crossing SET crops_purged_at = :at, version = version + 1
                         WHERE id IN (:ids) AND crops_purged_at IS NULL
                        """)
                .param("at", Timestamp.from(at))
                .param("ids", crossingIds)
                .update();
    }

    // ---- unknown items (R12) -----------------------------------------------------------------------------------

    /** Open UNKNOWN items whose last sighting is older than the cut-off become EXPIRED; returns their IDs. */
    public List<Long> expireUnknownItems(Instant cutoff, String reason, int limit) {
        return jdbc.sql("""
                        UPDATE attend.review_item SET status = 'EXPIRED', resolution_reason = :reason,
                               version = version + 1
                         WHERE id IN (SELECT id FROM attend.review_item
                                       WHERE type = 'UNKNOWN' AND status = 'OPEN' AND last_sighting_at < :cutoff
                                       ORDER BY id LIMIT :limit)
                           AND status = 'OPEN'
                        RETURNING id
                        """)
                .param("reason", reason)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /** The pending crossings of expired items get EXPIRED_NO_ACTION (no punch); returns their IDs. */
    public List<Long> expireCrossingsOf(Collection<Long> reviewItemIds, String effectReason) {
        if (reviewItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        UPDATE attend.crossing SET decision = 'EXPIRED_NO_ACTION', effect = 'IGNORED_UNKNOWN',
                               effect_reason = :reason, version = version + 1
                         WHERE review_item_id IN (:ids) AND decision = 'PENDING_REVIEW'
                        RETURNING id
                        """)
                .param("reason", effectReason)
                .param("ids", reviewItemIds)
                .query(Long.class)
                .list();
    }

    // ---- crossings and everything that references them -----------------------------------------------------------

    /** Crossings older than the cut-off, in ID order after {@code afterId}. */
    public List<Long> crossingsOlderThan(Instant cutoff, long afterId, int limit) {
        return jdbc.sql("""
                        SELECT id FROM attend.crossing
                         WHERE occurred_at < :cutoff AND id > :afterId
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("afterId", afterId)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /** Deletes the outbox rows of these crossings (any status); returns the deleted rows' statuses. */
    public List<String> deleteOutboxOfCrossings(Collection<Long> crossingIds) {
        return jdbc.sql("DELETE FROM attend.hrms_outbox WHERE crossing_id IN (:ids) RETURNING status")
                .param("ids", crossingIds)
                .query(String.class)
                .list();
    }

    public int deleteAttendanceDaysOfCrossings(Collection<Long> crossingIds) {
        return jdbc.sql("""
                        DELETE FROM attend.attendance_day
                         WHERE check_in_crossing_id IN (:ids) OR check_out_crossing_id IN (:ids)
                        """).param("ids", crossingIds).update();
    }

    public int deleteCorrectionsOfCrossings(Collection<Long> crossingIds) {
        return jdbc.sql("DELETE FROM attend.correction WHERE crossing_id IN (:ids)")
                .param("ids", crossingIds)
                .update();
    }

    /** Deletes the crossings; returns the review items they belonged to. */
    public List<Long> deleteCrossings(Collection<Long> crossingIds) {
        return jdbc
                .sql("DELETE FROM attend.crossing WHERE id IN (:ids) RETURNING review_item_id")
                .param("ids", crossingIds)
                .query((rs, n) -> rs.getObject("review_item_id", Long.class))
                .list()
                .stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Deletes those review items that no crossing refers to any more. */
    public int deleteOrphanedReviewItems(Collection<Long> reviewItemIds) {
        if (reviewItemIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        DELETE FROM attend.review_item r
                         WHERE r.id IN (:ids)
                           AND NOT EXISTS (SELECT 1 FROM attend.crossing c WHERE c.review_item_id = r.id)
                        """).param("ids", reviewItemIds).update();
    }

    /** Outbox rows of shift days before the cut-off that no crossing kept (any status); returns their statuses. */
    public List<String> deleteOutboxBefore(LocalDate day) {
        return jdbc.sql("DELETE FROM attend.hrms_outbox WHERE shift_day < :day RETURNING status")
                .param("day", day)
                .query(String.class)
                .list();
    }

    /** Attendance days before the cut-off that no crossing kept (absences, holidays, weekly offs). */
    public int deleteAttendanceDaysBefore(LocalDate day) {
        return jdbc.sql("DELETE FROM attend.attendance_day WHERE shift_day < :day")
                .param("day", day)
                .update();
    }

    // ---- audit ---------------------------------------------------------------------------------------------------

    /**
     * Purges the audit log through its SECURITY DEFINER function (the only way to delete audit entries). The database
     * refuses cut-offs within the last 30 days; the cut-off is capped there, so a clock ahead of the database's never
     * makes the purge fail.
     */
    public long purgeAudit(Instant cutoff) {
        return jdbc.sql("SELECT attend.purge_audit(LEAST(CAST(:cutoff AS timestamptz), now() - interval '30 days'))")
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .single();
    }

    // ---- notifications ---------------------------------------------------------------------------------------------

    public int deleteNotificationsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM attend.notification WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    public int deleteEmailsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM attend.email_outbox WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    // ---- enrolment
    // ---------------------------------------------------------------------------------------------------

    /** Finished sessions (and open ones long expired) older than the cut-off. */
    public List<Long> enrolmentSessionsBefore(Instant cutoff) {
        return jdbc.sql("""
                        SELECT id FROM attend.enrolment_session
                         WHERE (status <> 'OPEN' AND coalesce(finished_at, started_at) < :cutoff)
                            OR (status = 'OPEN' AND expires_at < :cutoff)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .list();
    }

    /**
     * Capture images of these sessions that no face template uses as its thumbnail; the {@link StoredFile#id()} is the
     * session's.
     */
    public List<StoredFile> captureImagesOfSessions(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT c.session_id, c.image_path FROM attend.enrolment_capture c
                         WHERE c.session_id IN (:ids) AND c.image_path IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM attend.face_template t WHERE t.thumbnail_path = c.image_path)
                        """)
                .param("ids", sessionIds)
                .query((rs, n) -> new StoredFile(rs.getLong("session_id"), rs.getString("image_path")))
                .list();
    }

    /** Deletes the sessions; their captures go with them (ON DELETE CASCADE). */
    public int deleteEnrolmentSessions(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM attend.enrolment_session WHERE id IN (:ids)")
                .param("ids", sessionIds)
                .update();
    }

    /** Templates deactivated before the cut-off (re-enrolment, deactivation), with their thumbnail paths. */
    public List<StoredFile> templatesDeactivatedBefore(Instant cutoff) {
        return jdbc.sql("""
                        SELECT id, thumbnail_path FROM attend.face_template
                         WHERE NOT active AND deactivated_at < :cutoff
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query((rs, n) -> new StoredFile(rs.getLong("id"), rs.getString("thumbnail_path")))
                .list();
    }

    public int deleteTemplates(Collection<Long> templateIds) {
        if (templateIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM attend.face_template WHERE id IN (:ids)")
                .param("ids", templateIds)
                .update();
    }

    // ---- camera health ---------------------------------------------------------------------------------------------

    public int deleteCameraHealthBefore(LocalDate day, Instant cutoff) {
        int daily = jdbc.sql("DELETE FROM attend.camera_health_daily WHERE day < :day")
                .param("day", day)
                .update();
        int outages = jdbc.sql("DELETE FROM attend.camera_outage WHERE ended_at IS NOT NULL AND ended_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
        return daily + outages;
    }

    // ---- erasure (R16, R17)
    // ------------------------------------------------------------------------------------------

    /**
     * Face profiles whose scheduled erasure is due (the account is still disabled; a re-enabled account has its
     * erasure cancelled by directory sync).
     */
    public List<UUID> erasuresDue(LocalDate today) {
        return jdbc.sql("""
                        SELECT fp.employee_id FROM attend.face_profile fp
                          JOIN attend.employee e ON e.id = fp.employee_id
                         WHERE fp.scheduled_erasure_on <= :today AND fp.status <> 'ERASED' AND NOT e.account_enabled
                         ORDER BY fp.scheduled_erasure_on, fp.employee_id
                        """).param("today", today).query(UUID.class).list();
    }

    public List<StoredFile> templatesOfEmployee(UUID employeeId) {
        return jdbc.sql("SELECT id, thumbnail_path FROM attend.face_template WHERE employee_id = :employee")
                .param("employee", employeeId)
                .query((rs, n) -> new StoredFile(rs.getLong("id"), rs.getString("thumbnail_path")))
                .list();
    }

    public List<StoredFile> captureImagesOfEmployee(UUID employeeId) {
        return jdbc.sql("""
                        SELECT c.id, c.image_path FROM attend.enrolment_capture c
                          JOIN attend.enrolment_session s ON s.id = c.session_id
                         WHERE s.employee_id = :employee
                        """)
                .param("employee", employeeId)
                .query((rs, n) -> new StoredFile(rs.getLong("id"), rs.getString("image_path")))
                .list();
    }

    public int deleteCaptures(Collection<Long> captureIds) {
        if (captureIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM attend.enrolment_capture WHERE id IN (:ids)")
                .param("ids", captureIds)
                .update();
    }

    /** The face profile's status before the erasure (for the audit entry), or null when it has none. */
    public String faceStatus(UUID employeeId) {
        return jdbc.sql("SELECT status FROM attend.face_profile WHERE employee_id = :employee")
                .param("employee", employeeId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public String displayName(UUID employeeId) {
        return jdbc.sql("SELECT display_name FROM attend.employee WHERE id = :employee")
                .param("employee", employeeId)
                .query(String.class)
                .single();
    }

    /** Face status ERASED (no scheduled erasure left) and tracking Not tracked / No face; history entry ERASED. */
    public void markErased(UUID employeeId, String reason, UUID actorId, String actorLabel, Instant at) {
        jdbc.sql("""
                        INSERT INTO attend.face_profile (employee_id, status, reason)
                        VALUES (:employee, 'ERASED', :reason)
                        ON CONFLICT (employee_id) DO UPDATE
                           SET status = 'ERASED', reason = EXCLUDED.reason, scheduled_erasure_on = NULL,
                               enrolled_at = NULL, version = attend.face_profile.version + 1
                        """).param("employee", employeeId).param("reason", reason).update();
        jdbc.sql("""
                        UPDATE attend.employment
                           SET tracking_mode = 'NOT_TRACKED', tracking_reason = 'NO_FACE', version = version + 1
                         WHERE employee_id = :employee
                           AND (tracking_mode <> 'NOT_TRACKED' OR tracking_reason IS DISTINCT FROM 'NO_FACE')
                        """).param("employee", employeeId).update();
        jdbc.sql("""
                        INSERT INTO attend.face_profile_history (employee_id, occurred_at, action, actor_id, actor_label, detail)
                        VALUES (:employee, :at, 'ERASED', :actorId, :actorLabel, :detail)
                        """)
                .param("employee", employeeId)
                .param("at", Timestamp.from(at))
                .param("actorId", actorId)
                .param("actorLabel", actorLabel)
                .param("detail", reason)
                .update();
    }

    private static CrossingCrops crops(ResultSet rs, int row) throws SQLException {
        return new CrossingCrops(
                rs.getLong("id"),
                rs.getString("face_crop_path"),
                rs.getString("face_crop_blur_path"),
                rs.getString("body_crop_path"),
                rs.getString("body_crop_blur_path"));
    }
}
