package com.krztechnologies.attend.attendance.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code attendance_day} (R7) and resolves the calendar's codes to the ids the row stores (read-only use of
 * the organisation tables, D-038).
 */
@Repository
public class AttendanceStore {

    private final JdbcClient jdbc;

    public AttendanceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The organisation ids behind the calendar's codes, loaded once per recompute run. */
    public record Ids(
            Map<String, Long> shifts,
            Map<String, Long> versions,
            Map<String, Long> allocations,
            Map<String, Long> holidays) {

        /** The key of {@link #versions}: shift code and effective date. */
        public static String version(String shiftCode, LocalDate effectiveFrom) {
            return shiftCode + "@" + effectiveFrom;
        }
    }

    /** One materialised employee-day; {@code status} and {@code statusCode} are null when the day does not apply. */
    public record DayRow(
            UUID employeeId,
            LocalDate shiftDay,
            long shiftId,
            long shiftVersionId,
            Long allocationId,
            String shiftSource,
            Instant windowStart,
            Instant shiftStart,
            Instant shiftEnd,
            Instant cutoffAt,
            Instant closeAt,
            boolean tracked,
            String trackingReason,
            Long holidayId,
            boolean weeklyOff,
            String status,
            String statusCode,
            Long checkInCrossingId,
            Long checkOutCrossingId,
            boolean checkOutFinal,
            boolean inside,
            boolean steppedOut,
            boolean late,
            Integer lateMinutes,
            boolean earlyExit,
            boolean missingCheckOut,
            Integer workedMinutes,
            Instant lastMovementAt,
            boolean finalised,
            Instant computedAt) {}

    /** An (employee, shift day) to recompute. */
    public record EmployeeDay(UUID employeeId, LocalDate shiftDay) {}

    /**
     * R7 time-driven transitions from {@code from} on: not yet in past the absent cutoff, not finalised past day close,
     * and inside or stepped out (worked time grows).
     */
    public List<EmployeeDay> timeDriven(LocalDate from, Instant now) {
        return jdbc.sql("""
                        SELECT employee_id, shift_day FROM attend.attendance_day
                         WHERE shift_day >= :from
                           AND ((status = 'NOT_YET_IN' AND cutoff_at <= :now)
                                OR (status IS NOT NULL AND NOT finalised AND close_at <= :now)
                                OR status IN ('INSIDE', 'STEPPED_OUT'))
                         ORDER BY employee_id, shift_day
                        """)
                .param("from", from)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new EmployeeDay(
                        rs.getObject("employee_id", UUID.class), rs.getObject("shift_day", LocalDate.class)))
                .list();
    }

    /** The (employee, day) pairs that have a row, among these employees and days. */
    public Set<EmployeeDay> existing(Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        if (employeeIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT employee_id, shift_day FROM attend.attendance_day
                         WHERE employee_id IN (:ids) AND shift_day BETWEEN :from AND :to
                        """)
                .param("ids", employeeIds)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new EmployeeDay(
                        rs.getObject("employee_id", UUID.class), rs.getObject("shift_day", LocalDate.class)))
                .list());
    }

    /** Employees whose attendance may change on {@code day} and who have no row for it yet. */
    public List<UUID> withoutRow(LocalDate day) {
        return jdbc.sql("""
                        SELECT e.id FROM attend.employee e
                         WHERE (e.account_enabled OR (e.left_on IS NOT NULL AND e.left_on >= :day))
                           AND NOT EXISTS (SELECT 1 FROM attend.attendance_day a
                                            WHERE a.employee_id = e.id AND a.shift_day = :day)
                        """).param("day", day).query(UUID.class).list();
    }

    public Ids ids() {
        Map<String, Long> shifts = new HashMap<>();
        jdbc.sql("SELECT code, id FROM attend.shift").query(rs -> {
            shifts.put(rs.getString("code"), rs.getLong("id"));
        });
        Map<String, Long> versions = new HashMap<>();
        jdbc.sql("""
                        SELECT s.code, v.effective_from, v.id
                          FROM attend.shift_version v JOIN attend.shift s ON s.id = v.shift_id
                        """).query(rs -> {
            versions.put(
                    Ids.version(rs.getString("code"), rs.getObject("effective_from", LocalDate.class)),
                    rs.getLong("id"));
        });
        Map<String, Long> allocations = new HashMap<>();
        jdbc.sql("SELECT code, id FROM attend.shift_allocation").query(rs -> {
            allocations.put(rs.getString("code"), rs.getLong("id"));
        });
        Map<String, Long> holidays = new HashMap<>();
        jdbc.sql("SELECT code, id FROM attend.holiday").query(rs -> {
            holidays.put(rs.getString("code"), rs.getLong("id"));
        });
        return new Ids(shifts, versions, allocations, holidays);
    }

    public void upsert(DayRow d) {
        jdbc.sql("""
                        INSERT INTO attend.attendance_day (
                            employee_id, shift_day, shift_id, shift_version_id, allocation_id, shift_source, window_start,
                            shift_start, shift_end, cutoff_at, close_at, tracked, tracking_reason, holiday_id, weekly_off,
                            status, status_code, check_in_crossing_id, check_out_crossing_id, check_out_final, inside,
                            stepped_out, late, late_minutes, early_exit, missing_check_out, worked_minutes, last_movement_at,
                            finalised, computed_at)
                        VALUES (
                            :employee, :day, :shift, :version, :allocation, :source, :windowStart, :shiftStart, :shiftEnd,
                            :cutoff, :close, :tracked, :trackingReason, :holiday, :weeklyOff, :status, :statusCode, :checkIn,
                            :checkOut, :checkOutFinal, :inside, :steppedOut, :late, :lateMinutes, :earlyExit,
                            :missingCheckOut, :worked, :lastMovement, :finalised, :computedAt)
                        ON CONFLICT (employee_id, shift_day) DO UPDATE SET
                            shift_id = EXCLUDED.shift_id, shift_version_id = EXCLUDED.shift_version_id,
                            allocation_id = EXCLUDED.allocation_id, shift_source = EXCLUDED.shift_source,
                            window_start = EXCLUDED.window_start, shift_start = EXCLUDED.shift_start,
                            shift_end = EXCLUDED.shift_end, cutoff_at = EXCLUDED.cutoff_at, close_at = EXCLUDED.close_at,
                            tracked = EXCLUDED.tracked, tracking_reason = EXCLUDED.tracking_reason,
                            holiday_id = EXCLUDED.holiday_id, weekly_off = EXCLUDED.weekly_off, status = EXCLUDED.status,
                            status_code = EXCLUDED.status_code, check_in_crossing_id = EXCLUDED.check_in_crossing_id,
                            check_out_crossing_id = EXCLUDED.check_out_crossing_id,
                            check_out_final = EXCLUDED.check_out_final, inside = EXCLUDED.inside,
                            stepped_out = EXCLUDED.stepped_out, late = EXCLUDED.late, late_minutes = EXCLUDED.late_minutes,
                            early_exit = EXCLUDED.early_exit, missing_check_out = EXCLUDED.missing_check_out,
                            worked_minutes = EXCLUDED.worked_minutes, last_movement_at = EXCLUDED.last_movement_at,
                            finalised = EXCLUDED.finalised, computed_at = EXCLUDED.computed_at
                        """)
                .param("employee", d.employeeId())
                .param("day", d.shiftDay())
                .param("shift", d.shiftId())
                .param("version", d.shiftVersionId())
                .param("allocation", d.allocationId())
                .param("source", d.shiftSource())
                .param("windowStart", ts(d.windowStart()))
                .param("shiftStart", ts(d.shiftStart()))
                .param("shiftEnd", ts(d.shiftEnd()))
                .param("cutoff", ts(d.cutoffAt()))
                .param("close", ts(d.closeAt()))
                .param("tracked", d.tracked())
                .param("trackingReason", d.trackingReason())
                .param("holiday", d.holidayId())
                .param("weeklyOff", d.weeklyOff())
                .param("status", d.status())
                .param("statusCode", d.statusCode())
                .param("checkIn", d.checkInCrossingId())
                .param("checkOut", d.checkOutCrossingId())
                .param("checkOutFinal", d.checkOutFinal())
                .param("inside", d.inside())
                .param("steppedOut", d.steppedOut())
                .param("late", d.late())
                .param("lateMinutes", d.lateMinutes())
                .param("earlyExit", d.earlyExit())
                .param("missingCheckOut", d.missingCheckOut())
                .param("worked", d.workedMinutes())
                .param("lastMovement", ts(d.lastMovementAt()))
                .param("finalised", d.finalised())
                .param("computedAt", ts(d.computedAt()))
                .update();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
