package com.krztechnologies.attend.crossing.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Corrections (R11): the correction rows and the crossing columns they change ({@code voided}, the direction). */
@Repository
public class CorrectionStore {

    private final JdbcClient jdbc;

    public CorrectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A crossing as a correction sees it (locked until the transaction ends). */
    public record CrossingRow(
            long id,
            String code,
            UUID employeeId,
            String employeeName,
            LocalDate shiftDay,
            String direction,
            String originalDirection,
            boolean voided,
            Instant occurredAt) {}

    /** A correction row. */
    public record CorrectionRow(long id, long crossingId, String kind, boolean undone) {}

    /** The crossing without a lock (the correction preview). */
    public Optional<CrossingRow> find(String code) {
        return select("c.code = :key", code, "");
    }

    public Optional<CrossingRow> lock(String code) {
        return select("c.code = :key", code, " FOR UPDATE OF c");
    }

    public Optional<CrossingRow> lock(long crossingId) {
        return select("c.id = :key", crossingId, " FOR UPDATE OF c");
    }

    private Optional<CrossingRow> select(String where, Object key, String lock) {
        String sql = """
                SELECT c.id, c.code, c.employee_id, e.display_name, c.shift_day, c.direction, c.original_direction,
                       c.voided, c.occurred_at
                  FROM attend.crossing c LEFT JOIN attend.employee e ON e.id = c.employee_id
                """ + " WHERE " + where + lock;
        return jdbc.sql(sql)
                .param("key", key)
                .query((rs, n) -> new CrossingRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getObject("shift_day", LocalDate.class),
                        rs.getString("direction"),
                        rs.getString("original_direction"),
                        rs.getBoolean("voided"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .optional();
    }

    public long insert(long crossingId, String kind, String reason, UUID actorId, Instant at) {
        return jdbc.sql("""
                        INSERT INTO attend.correction (crossing_id, kind, reason, actor_id, created_at)
                        VALUES (:crossing, :kind, :reason, :actor, :at)
                        RETURNING id
                        """)
                .param("crossing", crossingId)
                .param("kind", kind)
                .param("reason", reason)
                .param("actor", actorId)
                .param("at", Timestamp.from(at))
                .query(Long.class)
                .single();
    }

    public Optional<CorrectionRow> correction(long id) {
        return jdbc.sql(
                        "SELECT id, crossing_id, kind, undone_at IS NOT NULL AS undone FROM attend.correction WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new CorrectionRow(
                        rs.getLong("id"), rs.getLong("crossing_id"), rs.getString("kind"), rs.getBoolean("undone")))
                .optional();
    }

    public void markUndone(long correctionId, UUID by, Instant at) {
        jdbc.sql("UPDATE attend.correction SET undone_at = :at, undone_by = :by WHERE id = :id")
                .param("at", Timestamp.from(at))
                .param("by", by)
                .param("id", correctionId)
                .update();
    }

    /** Whether the crossing still has an active void after an undo. */
    public boolean hasActiveVoid(long crossingId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM attend.correction
                                        WHERE crossing_id = :id AND kind = 'VOID' AND undone_at IS NULL)
                        """).param("id", crossingId).query(Boolean.class).single();
    }

    public void setVoided(long crossingId, boolean voided) {
        jdbc.sql("UPDATE attend.crossing SET voided = :voided, version = version + 1 WHERE id = :id")
                .param("voided", voided)
                .param("id", crossingId)
                .update();
    }

    public void setDirection(long crossingId, String direction, String originalDirection) {
        jdbc.sql("""
                        UPDATE attend.crossing SET direction = :direction, original_direction = :original,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("direction", direction)
                .param("original", originalDirection)
                .param("id", crossingId)
                .update();
    }
}
