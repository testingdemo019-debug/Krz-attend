package com.krztechnologies.attend.attendance.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL of Today, the evacuation list, the attendance log and the day drawer: the materialised days with their
 * crossings and HRMS punches, and the people around them (read-only use of those tables, D-038).
 */
@Repository
public class AttendanceQueries {

    private static final String DAY_COLUMNS = """
            SELECT a.employee_id, a.shift_day, s.code AS shift_code, s.name::text AS shift_name, a.status, a.status_code,
                   a.tracked, a.finalised, a.late, a.late_minutes,
                   a.early_exit, a.missing_check_out, a.inside, a.check_out_final, a.worked_minutes, a.last_movement_at,
                   a.holiday_id IS NOT NULL AS holiday, a.weekly_off,
                   ci.occurred_at AS check_in_at, ci.method AS check_in_method, co.occurred_at AS check_out_at,
                   (SELECT c.direction FROM attend.crossing c
                     WHERE c.employee_id = a.employee_id AND c.shift_day = a.shift_day
                       AND c.occurred_at = a.last_movement_at AND c.effect IN ('CHECK_IN', 'CHECK_OUT', 'MOVEMENT')
                     ORDER BY c.id DESC LIMIT 1) AS last_direction,
                   (SELECT max(c.occurred_at) FROM attend.crossing c
                     WHERE c.employee_id = a.employee_id AND c.shift_day = a.shift_day AND c.direction = 'IN'
                       AND c.effect IN ('CHECK_IN', 'MOVEMENT')) AS last_in_at,
                   (SELECT o.status FROM attend.hrms_outbox o
                     WHERE o.employee_id = a.employee_id AND o.shift_day = a.shift_day AND o.kind = 'PUNCH'
                     ORDER BY (o.payload ->> 'type') = 'CHECK_OUT' DESC, o.id DESC LIMIT 1) AS hrms_status
              FROM attend.attendance_day a
              JOIN attend.shift s ON s.id = a.shift_id
              LEFT JOIN attend.crossing ci ON ci.id = a.check_in_crossing_id
              LEFT JOIN attend.crossing co ON co.id = a.check_out_crossing_id
            """;

    private final JdbcClient jdbc;

    public AttendanceQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One materialised employee-day with what Today and the log show.
     *
     * @param lastDirection the direction of the last counted crossing
     * @param lastInAt the last counted IN (evacuation list)
     * @param hrmsStatus the check-out punch's outbox status, else the check-in's
     */
    public record DayRow(
            UUID employeeId,
            LocalDate shiftDay,
            String shiftCode,
            String shiftName,
            String status,
            String statusCode,
            boolean tracked,
            boolean finalised,
            boolean late,
            Integer lateMinutes,
            boolean earlyExit,
            boolean missingCheckOut,
            boolean inside,
            boolean checkOutFinal,
            Integer workedMinutes,
            Instant lastMovementAt,
            boolean holiday,
            boolean weeklyOff,
            Instant checkInAt,
            String checkInMethod,
            Instant checkOutAt,
            String lastDirection,
            Instant lastInAt,
            String hrmsStatus) {}

    /** The employee data the attendance pages show. */
    public record Person(
            UUID id,
            String name,
            String employeeId,
            String departmentCode,
            String departmentName,
            String locationCode,
            String locationName,
            String employmentType) {}

    /** An HRMS outbox row of a day ({@code type} and {@code timestamp} are null for a VOID entry). */
    public record Punch(String punchId, String kind, String type, String timestamp, String status) {}

    /** The rows of these employees from {@code from} to {@code to}. */
    public List<DayRow> days(Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(DAY_COLUMNS + " WHERE a.employee_id IN (:ids) AND a.shift_day BETWEEN :from AND :to")
                .param("ids", employeeIds)
                .param("from", from)
                .param("to", to)
                .query(AttendanceQueries::dayRow)
                .list();
    }

    public List<Person> people(Collection<UUID> employeeIds) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT e.id, e.display_name, m.hrms_employee_id, d.code AS department_code,
                               d.name::text AS department_name, l.code AS location_code, l.name::text AS location_name,
                               m.employment_type
                          FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                          LEFT JOIN attend.department d ON d.id = m.department_id
                          LEFT JOIN attend.location l ON l.id = m.location_id
                         WHERE e.id IN (:ids)
                        """)
                .param("ids", employeeIds)
                .query((rs, n) -> new Person(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("hrms_employee_id"),
                        rs.getString("department_code"),
                        rs.getString("department_name"),
                        rs.getString("location_code"),
                        rs.getString("location_name"),
                        rs.getString("employment_type")))
                .list();
    }

    /** The outbox rows of one employee-day, oldest first. */
    public List<Punch> punches(UUID employeeId, LocalDate day) {
        return jdbc.sql("""
                        SELECT punch_id, kind, payload ->> 'type' AS type, payload ->> 'timestamp' AS ts, status
                          FROM attend.hrms_outbox
                         WHERE employee_id = :employee AND shift_day = :day
                         ORDER BY id
                        """)
                .param("employee", employeeId)
                .param("day", day)
                .query((rs, n) -> new Punch(
                        rs.getString("punch_id"),
                        rs.getString("kind"),
                        rs.getString("type"),
                        rs.getString("ts"),
                        rs.getString("status")))
                .list();
    }

    /** Unidentified INs since {@code from} still waiting in the Review Queue (the evacuation list's warning). */
    public int unidentifiedInsSince(Instant from) {
        return jdbc.sql("""
                        SELECT count(*) FROM attend.crossing
                         WHERE occurred_at >= :from AND employee_id IS NULL AND direction = 'IN' AND NOT u_turn
                           AND decision = 'PENDING_REVIEW'
                        """)
                .param("from", Timestamp.from(from))
                .query(Integer.class)
                .single();
    }

    /** The names of the enabled cameras (the evacuation list's header). */
    public List<String> cameraNames() {
        return jdbc.sql("SELECT name FROM attend.camera WHERE enabled ORDER BY code")
                .query(String.class)
                .list();
    }

    private static DayRow dayRow(ResultSet rs, int n) throws SQLException {
        return new DayRow(
                rs.getObject("employee_id", UUID.class),
                rs.getObject("shift_day", LocalDate.class),
                rs.getString("shift_code"),
                rs.getString("shift_name"),
                rs.getString("status"),
                rs.getString("status_code"),
                rs.getBoolean("tracked"),
                rs.getBoolean("finalised"),
                rs.getBoolean("late"),
                (Integer) rs.getObject("late_minutes"),
                rs.getBoolean("early_exit"),
                rs.getBoolean("missing_check_out"),
                rs.getBoolean("inside"),
                rs.getBoolean("check_out_final"),
                (Integer) rs.getObject("worked_minutes"),
                instant(rs, "last_movement_at"),
                rs.getBoolean("holiday"),
                rs.getBoolean("weekly_off"),
                instant(rs, "check_in_at"),
                rs.getString("check_in_method"),
                instant(rs, "check_out_at"),
                rs.getString("last_direction"),
                instant(rs, "last_in_at"),
                rs.getString("hrms_status"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
