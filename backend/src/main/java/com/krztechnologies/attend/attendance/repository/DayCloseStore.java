package com.krztechnologies.attend.attendance.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code day_close_run}: one row per (shift, shift day), idempotent by its unique key (R13). */
@Repository
public class DayCloseStore {

    private final JdbcClient jdbc;

    public DayCloseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored run with its shift and who triggered it. */
    public record RunRow(
            String code,
            String shiftCode,
            String shiftName,
            LocalDate shiftDay,
            Instant ranAt,
            String trigger,
            String triggeredBy,
            int employees,
            int finalised,
            int absent,
            int missingCheckOuts) {}

    /** What one run counted. */
    public record NewRun(
            String code,
            long shiftId,
            LocalDate shiftDay,
            Instant ranAt,
            String trigger,
            UUID triggeredBy,
            int employees,
            int finalised,
            int absent,
            int missingCheckOuts) {}

    public boolean exists(long shiftId, LocalDate day) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM attend.day_close_run WHERE shift_id = :shift AND shift_day = :day)")
                .param("shift", shiftId)
                .param("day", day)
                .query(Boolean.class)
                .single();
    }

    /** Stores the run; false when another node stored it first. */
    public boolean insert(NewRun run) {
        return jdbc.sql("""
                        INSERT INTO attend.day_close_run (code, shift_id, shift_day, ran_at, trigger, triggered_by, employees,
                                                          finalised, absent, missing_check_outs)
                        VALUES (:code, :shift, :day, :ranAt, :trigger, :by, :employees, :finalised, :absent, :missing)
                        ON CONFLICT (shift_id, shift_day) DO NOTHING
                        """)
                        .param("code", run.code())
                        .param("shift", run.shiftId())
                        .param("day", run.shiftDay())
                        .param("ranAt", Timestamp.from(run.ranAt()))
                        .param("trigger", run.trigger())
                        .param("by", run.triggeredBy())
                        .param("employees", run.employees())
                        .param("finalised", run.finalised())
                        .param("absent", run.absent())
                        .param("missing", run.missingCheckOuts())
                        .update()
                == 1;
    }

    public List<RunRow> list(long offset, int limit) {
        return jdbc.sql("""
                        SELECT r.code, s.code AS shift_code, s.name::text AS shift_name, r.shift_day, r.ran_at, r.trigger,
                               e.display_name AS triggered_by, r.employees, r.finalised, r.absent, r.missing_check_outs
                          FROM attend.day_close_run r
                          JOIN attend.shift s ON s.id = r.shift_id
                          LEFT JOIN attend.employee e ON e.id = r.triggered_by
                         ORDER BY r.ran_at DESC, r.id DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("limit", limit)
                .param("offset", offset)
                .query(DayCloseStore::row)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM attend.day_close_run")
                .query(Long.class)
                .single();
    }

    private static RunRow row(ResultSet rs, int n) throws SQLException {
        return new RunRow(
                rs.getString("code"),
                rs.getString("shift_code"),
                rs.getString("shift_name"),
                rs.getObject("shift_day", LocalDate.class),
                rs.getObject("ran_at", OffsetDateTime.class).toInstant(),
                rs.getString("trigger"),
                rs.getString("triggered_by"),
                rs.getInt("employees"),
                rs.getInt("finalised"),
                rs.getInt("absent"),
                rs.getInt("missing_check_outs"));
    }
}
