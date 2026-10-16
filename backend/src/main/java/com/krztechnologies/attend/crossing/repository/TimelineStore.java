package com.krztechnologies.attend.crossing.repository;

import com.krztechnologies.attend.rules.service.Effect;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The identified crossings of an employee's shift day, and the effects the punch rules give them. */
@Repository
public class TimelineStore {

    private final JdbcClient jdbc;

    public TimelineStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One identified crossing with its camera and an active void correction.
     *
     * @param direction after any flip correction
     * @param voidedBy the name of who voided it, when voided
     */
    public record Row(
            long id,
            String code,
            Instant occurredAt,
            String direction,
            String originalDirection,
            String method,
            boolean uTurn,
            String cameraCode,
            String cameraName,
            String cameraMode,
            boolean voided,
            String voidedBy,
            String voidReason) {}

    public List<Row> identified(UUID employeeId, LocalDate shiftDay) {
        return jdbc.sql("""
                        SELECT c.id, c.code, c.occurred_at, c.direction, c.original_direction, c.method, c.u_turn, cam.code AS camera_code,
                               cam.name AS camera_name, cam.direction_mode, c.voided, v.reason AS void_reason,
                               ve.display_name AS voided_by
                          FROM attend.crossing c
                          JOIN attend.camera cam ON cam.id = c.camera_id
                          LEFT JOIN LATERAL (
                              SELECT k.reason, k.actor_id FROM attend.correction k
                               WHERE k.crossing_id = c.id AND k.kind = 'VOID' AND k.undone_at IS NULL
                               ORDER BY k.created_at DESC LIMIT 1) v ON true
                          LEFT JOIN attend.employee ve ON ve.id = v.actor_id
                         WHERE c.employee_id = :employee AND c.shift_day = :day
                         ORDER BY c.occurred_at, c.id
                        """)
                .param("employee", employeeId)
                .param("day", shiftDay)
                .query(TimelineStore::row)
                .list();
    }

    /** An identified crossing's time and the shift day it is filed under. */
    public record Filed(long id, Instant occurredAt, LocalDate shiftDay) {}

    /** The employee's identified crossings between two instants. */
    public List<Filed> filed(UUID employeeId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT id, occurred_at, shift_day FROM attend.crossing
                         WHERE employee_id = :employee AND occurred_at >= :from AND occurred_at < :to
                        """)
                .param("employee", employeeId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Filed(
                        rs.getLong("id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("shift_day", LocalDate.class)))
                .list();
    }

    public void setShiftDay(long crossingId, LocalDate shiftDay) {
        jdbc.sql("UPDATE attend.crossing SET shift_day = :day, version = version + 1 WHERE id = :id")
                .param("day", shiftDay)
                .param("id", crossingId)
                .update();
    }

    /** Stores an effect; returns false when it was already stored. */
    public boolean setEffect(long crossingId, Effect effect, String reason) {
        return jdbc.sql("""
                        UPDATE attend.crossing SET effect = :effect, effect_reason = :reason
                         WHERE id = :id AND (effect IS DISTINCT FROM :effect OR effect_reason IS DISTINCT FROM :reason)
                        """)
                        .param("effect", effect.name())
                        .param("reason", reason)
                        .param("id", crossingId)
                        .update()
                > 0;
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("direction"),
                rs.getString("original_direction"),
                rs.getString("method"),
                rs.getBoolean("u_turn"),
                rs.getString("camera_code"),
                rs.getString("camera_name"),
                rs.getString("direction_mode"),
                rs.getBoolean("voided"),
                rs.getString("voided_by"),
                rs.getString("void_reason"));
    }
}
