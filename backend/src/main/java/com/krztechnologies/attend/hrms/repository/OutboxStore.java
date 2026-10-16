package com.krztechnologies.attend.hrms.repository;

import com.krztechnologies.attend.rules.service.OutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The HRMS outbox: the rows of one (employee, shift day) for reconciliation, where the crossing state behind each punch
 * (identity, active void or flip; read-only use of {@code crossing} and {@code correction}, D-038) feeds the void
 * reasons of R8; and the sender's and the outbox page's writes (due entries, attempts, retry, release).
 */
@Repository
public class OutboxStore {

    /**
     * PENDING entries whose next attempt is due. An entry waits while an older entry of the same punch ID is unsent, so a
     * VOID always reaches HRMS before the punch that replaces it.
     */
    private static final String DUE = """
              FROM attend.hrms_outbox o
             WHERE o.status = 'PENDING' AND o.next_attempt_at <= :now
               AND NOT EXISTS (SELECT 1 FROM attend.hrms_outbox p
                                WHERE p.punch_id = o.punch_id AND p.id < o.id
                                  AND p.status IN ('PENDING', 'FAILED', 'HELD_NOT_CONNECTED'))
            """;

    private final JdbcClient jdbc;

    public OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A live PUNCH row (not voided).
     *
     * @param crossingExists false when the crossing behind the punch no longer exists
     * @param crossingEmployeeId the crossing's identified employee now, or null
     * @param voidReason the reason of an active void correction of that crossing, or null
     * @param flipReason the reason of an active direction flip of that crossing, or null
     */
    public record LivePunch(
            long id,
            String code,
            String punchId,
            OutboxStatus status,
            String payload,
            Long crossingId,
            boolean crossingExists,
            UUID crossingEmployeeId,
            String voidReason,
            String flipReason) {}

    /** A new row. */
    public record NewRow(
            String code,
            String kind,
            String punchId,
            UUID employeeId,
            LocalDate shiftDay,
            Long crossingId,
            String payload,
            OutboxStatus status,
            Instant dueAt,
            Instant nextAttemptAt) {}

    public List<LivePunch> livePunches(UUID employeeId, LocalDate day) {
        return jdbc.sql("""
                        SELECT o.id, o.code, o.punch_id, o.status, o.payload::text AS payload, o.crossing_id,
                               c.id IS NOT NULL AS crossing_exists, c.employee_id AS crossing_employee_id,
                               (SELECT k.reason FROM attend.correction k
                                 WHERE k.crossing_id = c.id AND k.kind = 'VOID' AND k.undone_at IS NULL
                                 ORDER BY k.created_at DESC LIMIT 1) AS void_reason,
                               (SELECT k.reason FROM attend.correction k
                                 WHERE k.crossing_id = c.id AND k.kind = 'FLIP' AND k.undone_at IS NULL
                                 ORDER BY k.created_at DESC LIMIT 1) AS flip_reason
                          FROM attend.hrms_outbox o
                          LEFT JOIN attend.crossing c ON c.id = o.crossing_id
                         WHERE o.employee_id = :employee AND o.shift_day = :day AND o.kind = 'PUNCH' AND o.status <> 'VOIDED'
                         ORDER BY o.id
                        """)
                .param("employee", employeeId)
                .param("day", day)
                .query(OutboxStore::livePunch)
                .list();
    }

    /** Whether a punch of the day has reached HRMS (a correction then voids and re-sends). */
    public boolean hasSent(UUID employeeId, LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM attend.hrms_outbox
                                        WHERE employee_id = :employee AND shift_day = :day AND kind = 'PUNCH'
                                          AND status = 'SENT')
                        """)
                .param("employee", employeeId)
                .param("day", day)
                .query(Boolean.class)
                .single();
    }

    public void insert(NewRow row) {
        jdbc.sql("""
                        INSERT INTO attend.hrms_outbox (code, kind, punch_id, employee_id, shift_day, crossing_id, payload, status,
                                                        due_at, next_attempt_at)
                        VALUES (:code, :kind, :punchId, :employee, :day, :crossing, CAST(:payload AS jsonb), :status, :due,
                                :next)
                        """)
                .param("code", row.code())
                .param("kind", row.kind())
                .param("punchId", row.punchId())
                .param("employee", row.employeeId())
                .param("day", row.shiftDay())
                .param("crossing", row.crossingId())
                .param("payload", row.payload())
                .param("status", row.status().name())
                .param("due", Timestamp.from(row.dueAt()))
                .param("next", row.nextAttemptAt() == null ? null : Timestamp.from(row.nextAttemptAt()))
                .update();
    }

    /**
     * A row that is not sent yet gets a new payload and status.
     *
     * @param nextAttemptAt when it becomes due now, or null to keep its schedule
     */
    public void update(long id, String payload, OutboxStatus status, Instant nextAttemptAt) {
        jdbc.sql("""
                        UPDATE attend.hrms_outbox
                           SET payload = CAST(:payload AS jsonb), status = :status,
                               next_attempt_at = coalesce(:next, next_attempt_at), version = version + 1
                         WHERE id = :id
                        """)
                .param("payload", payload)
                .param("status", status.name())
                .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
                .param("id", id)
                .update();
    }

    public void markVoided(long id, Instant at) {
        jdbc.sql(
                        "UPDATE attend.hrms_outbox SET status = 'VOIDED', voided_at = :at, version = version + 1 WHERE id = :id")
                .param("at", Timestamp.from(at))
                .param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM attend.hrms_outbox WHERE id = :id")
                .param("id", id)
                .update();
    }

    /** An entry due for sending. */
    public record Due(long id, String code, String kind, String punchId, String payload, int attempts) {}

    /** The due entries, oldest first. */
    public List<Long> dueIds(Instant now, int limit) {
        return jdbc.sql("SELECT o.id " + DUE + " ORDER BY o.due_at, o.id LIMIT :limit")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /** The entry if it is still due, locked (skipped when someone else holds it). */
    public Optional<Due> lockDue(long id, Instant now) {
        return jdbc.sql("SELECT o.id, o.code, o.kind, o.punch_id, o.payload::text AS payload, o.attempts " + DUE
                        + " AND o.id = :id FOR UPDATE OF o SKIP LOCKED")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .query((rs, n) -> new Due(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("kind"),
                        rs.getString("punch_id"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
                .optional();
    }

    public void markSent(long id, int attempts, Instant at) {
        jdbc.sql("""
                        UPDATE attend.hrms_outbox
                           SET status = 'SENT', sent_at = :at, attempts = :attempts, last_attempt_at = :at,
                               last_error = NULL, next_attempt_at = NULL, version = version + 1
                         WHERE id = :id
                        """)
                .param("at", Timestamp.from(at))
                .param("attempts", attempts)
                .param("id", id)
                .update();
    }

    /**
     * A failed attempt: the entry stays PENDING until {@code nextAttemptAt}, or becomes FAILED after the last attempt
     * ({@code nextAttemptAt} null).
     */
    public void markAttemptFailed(long id, int attempts, String error, Instant at, Instant nextAttemptAt) {
        jdbc.sql("""
                        UPDATE attend.hrms_outbox
                           SET status = CASE WHEN CAST(:next AS timestamptz) IS NULL THEN 'FAILED' ELSE 'PENDING' END,
                               attempts = :attempts, last_error = :error, last_attempt_at = :at, next_attempt_at = :next,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("attempts", attempts)
                .param("error", error)
                .param("at", Timestamp.from(at))
                .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
                .param("id", id)
                .update();
    }

    /** An entry by its OB code, locked. */
    public record Entry(long id, String code, String punchId, OutboxStatus status) {}

    public Optional<Entry> lock(String code) {
        return jdbc.sql("SELECT id, code, punch_id, status FROM attend.hrms_outbox WHERE code = :code FOR UPDATE")
                .param("code", code)
                .query((rs, n) -> new Entry(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("punch_id"),
                        OutboxStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /**
     * Queues entries in {@code from} again as {@code to} (PENDING, or HELD while HRMS is off) with fresh attempts, due at
     * {@code at}; null {@code id} = every entry in {@code from}. Returns how many.
     */
    public int requeue(Long id, OutboxStatus from, OutboxStatus to, Instant at) {
        return jdbc.sql("""
                        UPDATE attend.hrms_outbox
                           SET status = :to, attempts = 0, last_error = NULL, next_attempt_at = :at, version = version + 1
                         WHERE status = :from AND (CAST(:id AS bigint) IS NULL OR id = :id)
                        """)
                .param("to", to.name())
                .param("from", from.name())
                .param("at", Timestamp.from(at))
                .param("id", id)
                .update();
    }

    private static LivePunch livePunch(ResultSet rs, int n) throws SQLException {
        return new LivePunch(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("punch_id"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getString("payload"),
                (Long) rs.getObject("crossing_id"),
                rs.getBoolean("crossing_exists"),
                rs.getObject("crossing_employee_id", UUID.class),
                rs.getString("void_reason"),
                rs.getString("flip_reason"));
    }
}
