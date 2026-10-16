package com.krztechnologies.attend.enrolment.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Enrolment sessions with their captures (unsaved until the session completes), liveness failures and lockouts (R15),
 * and the enrolment queue.
 */
@Repository
public class EnrolmentStore {

    private static final String SESSION_COLUMNS = """
            SELECT id, code, employee_id, method, status, started_by, started_at, finished_at, expires_at, consent_id,
                   lookalike_confirmed
              FROM attend.enrolment_session
            """;

    private static final String CAPTURE_COLUMNS = """
            SELECT id, session_id, pose, image_path, embedding_enc, quality::text AS quality, liveness_passed,
                   liveness_score, accepted, reject_reason, created_at
              FROM attend.enrolment_capture
            """;

    private final JdbcClient jdbc;

    public EnrolmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An enrolment session. */
    public record Session(
            long id,
            String code,
            UUID employeeId,
            String method,
            String status,
            UUID startedBy,
            Instant startedAt,
            Instant finishedAt,
            Instant expiresAt,
            Long consentId,
            boolean lookalikeConfirmed) {}

    /**
     * A capture of a session.
     *
     * @param embeddingEnc the encrypted embedding of an accepted capture, else null
     * @param quality the worker's measurement (with the model and dimension) as JSON
     */
    public record Capture(
            long id,
            long sessionId,
            String pose,
            String imagePath,
            byte[] embeddingEnc,
            String quality,
            Boolean livenessPassed,
            BigDecimal livenessScore,
            boolean accepted,
            String rejectReason,
            Instant createdAt) {}

    /** A capture to store. */
    public record NewCapture(
            long sessionId,
            String pose,
            String imagePath,
            byte[] embeddingEnc,
            String quality,
            Boolean livenessPassed,
            BigDecimal livenessScore,
            boolean accepted,
            String rejectReason,
            Instant at) {}

    public Session start(UUID employeeId, String method, UUID startedBy, Instant at, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO attend.enrolment_session (code, employee_id, method, started_by, started_at, expires_at)
                        VALUES (attend.next_code('ES-', 'attend.seq_enrolment_session_code', 6), :employee, :method, :by,
                                :at, :expires)
                        RETURNING id, code, employee_id, method, status, started_by, started_at, finished_at, expires_at,
                                  consent_id, lookalike_confirmed
                        """)
                .param("employee", employeeId)
                .param("method", method)
                .param("by", startedBy)
                .param("at", Timestamp.from(at))
                .param("expires", Timestamp.from(expiresAt))
                .query(EnrolmentStore::session)
                .single();
    }

    /** The session as it is (no lock). */
    public Optional<Session> find(String code) {
        return jdbc.sql(SESSION_COLUMNS + " WHERE code = :code")
                .param("code", code)
                .query(EnrolmentStore::session)
                .optional();
    }

    /** The session, locked until the transaction ends. */
    public Optional<Session> lock(String code) {
        return jdbc.sql(SESSION_COLUMNS + " WHERE code = :code FOR UPDATE")
                .param("code", code)
                .query(EnrolmentStore::session)
                .optional();
    }

    /** The employee's open session, locked. */
    public Optional<Session> openOf(UUID employeeId) {
        return jdbc.sql(SESSION_COLUMNS + " WHERE employee_id = :employee AND status = 'OPEN' FOR UPDATE")
                .param("employee", employeeId)
                .query(EnrolmentStore::session)
                .optional();
    }

    /** Open sessions whose time ran out. */
    public List<Session> expired(Instant now) {
        return jdbc.sql(SESSION_COLUMNS + " WHERE status = 'OPEN' AND expires_at < :now FOR UPDATE SKIP LOCKED")
                .param("now", Timestamp.from(now))
                .query(EnrolmentStore::session)
                .list();
    }

    public void consentGiven(long sessionId, long consentId) {
        jdbc.sql("UPDATE attend.enrolment_session SET consent_id = :consent, version = version + 1 WHERE id = :id")
                .param("consent", consentId)
                .param("id", sessionId)
                .update();
    }

    public void lookalikeConfirmed(long sessionId) {
        jdbc.sql("UPDATE attend.enrolment_session SET lookalike_confirmed = true, version = version + 1 WHERE id = :id")
                .param("id", sessionId)
                .update();
    }

    /** Ends the session: COMPLETED, CANCELLED, DECLINED or EXPIRED. */
    public void finish(long sessionId, String status, Instant at) {
        jdbc.sql("""
                        UPDATE attend.enrolment_session SET status = :status, finished_at = :at, version = version + 1
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("at", Timestamp.from(at))
                .param("id", sessionId)
                .update();
    }

    public long addCapture(NewCapture c) {
        return jdbc.sql("""
                        INSERT INTO attend.enrolment_capture (session_id, pose, image_path, embedding_enc, quality,
                                                              liveness_passed, liveness_score, accepted, reject_reason,
                                                              created_at)
                        VALUES (:session, :pose, :image, :embedding, CAST(:quality AS jsonb), :livenessPassed,
                                :livenessScore, :accepted, :reason, :at)
                        RETURNING id
                        """)
                .param("session", c.sessionId())
                .param("pose", c.pose())
                .param("image", c.imagePath())
                .param("embedding", c.embeddingEnc())
                .param("quality", c.quality())
                .param("livenessPassed", c.livenessPassed())
                .param("livenessScore", c.livenessScore())
                .param("accepted", c.accepted())
                .param("reason", c.rejectReason())
                .param("at", Timestamp.from(c.at()))
                .query(Long.class)
                .single();
    }

    public List<Capture> captures(long sessionId) {
        return jdbc.sql(CAPTURE_COLUMNS + " WHERE session_id = :session ORDER BY id")
                .param("session", sessionId)
                .query(EnrolmentStore::capture)
                .list();
    }

    public void deleteCapture(long captureId) {
        jdbc.sql("DELETE FROM attend.enrolment_capture WHERE id = :id")
                .param("id", captureId)
                .update();
    }

    public void deleteCaptures(long sessionId) {
        jdbc.sql("DELETE FROM attend.enrolment_capture WHERE session_id = :session")
                .param("session", sessionId)
                .update();
    }

    public void livenessFailure(UUID employeeId, long sessionId, UUID operatorId, Instant at) {
        jdbc.sql("""
                        INSERT INTO attend.liveness_failure (employee_id, session_id, operator_id, occurred_at)
                        VALUES (:employee, :session, :operator, :at)
                        """)
                .param("employee", employeeId)
                .param("session", sessionId)
                .param("operator", operatorId)
                .param("at", Timestamp.from(at))
                .update();
    }

    public int livenessFailuresSince(UUID employeeId, Instant since) {
        return jdbc.sql(
                        "SELECT count(*) FROM attend.liveness_failure WHERE employee_id = :employee AND occurred_at >= :since")
                .param("employee", employeeId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** When the employee's enrolment lock ends, if it is locked at {@code now}. */
    public Optional<Instant> lockedUntil(UUID employeeId, Instant now) {
        return jdbc.sql(
                        "SELECT locked_until FROM attend.enrolment_lockout WHERE employee_id = :employee AND locked_until > :now")
                .param("employee", employeeId)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> instant(rs, "locked_until"))
                .optional();
    }

    public void lock(UUID employeeId, Instant until, String reason) {
        jdbc.sql("""
                        INSERT INTO attend.enrolment_lockout (employee_id, locked_until, reason)
                        VALUES (:employee, :until, :reason)
                        ON CONFLICT (employee_id) DO UPDATE SET locked_until = EXCLUDED.locked_until, reason = EXCLUDED.reason
                        """)
                .param("employee", employeeId)
                .param("until", Timestamp.from(until))
                .param("reason", reason)
                .update();
    }

    /** Lifts the lock and forgets the failures that led to it; false when there was no lock. */
    public boolean unlock(UUID employeeId, Instant now) {
        jdbc.sql("DELETE FROM attend.liveness_failure WHERE employee_id = :employee")
                .param("employee", employeeId)
                .update();
        return jdbc.sql("DELETE FROM attend.enrolment_lockout WHERE employee_id = :employee AND locked_until > :now")
                        .param("employee", employeeId)
                        .param("now", Timestamp.from(now))
                        .update()
                > 0;
    }

    /**
     * The enrolment queue: enabled employees at a location with an enabled camera whose face profile is not enrolled,
     * needs re-enrolment or waits for consent (read-only use of the employee and camera tables, D-038).
     */
    public List<UUID> queue() {
        return jdbc.sql("""
                        SELECT e.id
                          FROM attend.employee e
                          JOIN attend.employment m ON m.employee_id = e.id
                          LEFT JOIN attend.face_profile f ON f.employee_id = e.id
                         WHERE e.account_enabled
                           AND coalesce(f.status, 'NOT_ENROLLED') IN ('NOT_ENROLLED', 'NEEDS_REENROL', 'CONSENT_PENDING')
                           AND EXISTS (SELECT 1 FROM attend.camera c JOIN attend.entry_point p ON p.id = c.entry_point_id
                                        WHERE c.enabled AND p.location_id = m.location_id)
                        """).query(UUID.class).list();
    }

    /** The names of the locations with an enabled camera (read-only use of the camera tables, D-038). */
    public List<String> cameraLocations() {
        return jdbc.sql("""
                        SELECT DISTINCT l.name::text AS name FROM attend.location l
                          JOIN attend.entry_point p ON p.location_id = l.id
                          JOIN attend.camera c ON c.entry_point_id = p.id
                         WHERE c.enabled
                         ORDER BY 1
                        """).query(String.class).list();
    }

    private static Session session(ResultSet rs, int n) throws SQLException {
        Object consent = rs.getObject("consent_id");
        return new Session(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("method"),
                rs.getString("status"),
                rs.getObject("started_by", UUID.class),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "expires_at"),
                consent == null ? null : ((Number) consent).longValue(),
                rs.getBoolean("lookalike_confirmed"));
    }

    private static Capture capture(ResultSet rs, int n) throws SQLException {
        return new Capture(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getString("pose"),
                rs.getString("image_path"),
                rs.getBytes("embedding_enc"),
                rs.getString("quality"),
                (Boolean) rs.getObject("liveness_passed"),
                rs.getBigDecimal("liveness_score"),
                rs.getBoolean("accepted"),
                rs.getString("reject_reason"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
