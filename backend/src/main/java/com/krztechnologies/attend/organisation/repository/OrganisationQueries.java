package com.krztechnologies.attend.organisation.repository;

import com.krztechnologies.attend.rules.service.ShiftCalendar;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL over tables of other modules that the organisation screens count or list: employees with their
 * employment (members, heads, people on a shift), cameras per entry point, and the audit entries of allocations. Plain
 * queries instead of service calls, because {@code employee} depends on {@code organisation} and not the reverse.
 */
@Repository
public class OrganisationQueries {

    private static final String PEOPLE = """
            SELECT e.id, e.display_name, e.account_enabled, e.left_on, m.department_id, m.location_id, m.join_date,
                   m.end_date, m.hrms_employee_id, m.designation
              FROM attend.employee e
              LEFT JOIN attend.employment m ON m.employee_id = e.id
            """;

    private final JdbcClient jdbc;

    public OrganisationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An employee as the organisation screens see them.
     *
     * @param departmentId null when not in a department (or no employment details yet)
     * @param employeeNumber HRMS Employee ID, null when missing
     */
    public record PersonRow(
            UUID id,
            String displayName,
            boolean enabled,
            LocalDate leftOn,
            Long departmentId,
            Long locationId,
            LocalDate joinDate,
            LocalDate endDate,
            String employeeNumber,
            String designation) {

        public ShiftCalendar.Person person() {
            return new ShiftCalendar.Person(id, departmentId, locationId);
        }

        /** Enabled, and employed on {@code day} as far as the employment dates are known. */
        public boolean worksOn(LocalDate day) {
            return enabled
                    && (joinDate == null || !joinDate.isAfter(day))
                    && (endDate == null || !endDate.isBefore(day));
        }
    }

    /** Cameras mounted at an entry point; only enabled cameras count as "has a camera" (R5). */
    public record CameraCount(int total, int enabled) {}

    /** One audit entry about an allocation (its target starts with the allocation code). */
    public record AllocationChange(
            String allocationCode, Instant at, String actor, String action, String before, String after) {}

    /** Everyone, ordered by name. */
    public List<PersonRow> people() {
        return jdbc.sql(PEOPLE + " ORDER BY e.display_name")
                .query(OrganisationQueries::person)
                .list();
    }

    public List<PersonRow> people(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(PEOPLE + " WHERE e.id IN (:ids) ORDER BY e.display_name")
                .param("ids", ids)
                .query(OrganisationQueries::person)
                .list();
    }

    /** Entry point id → its cameras. */
    public Map<Long, CameraCount> camerasByEntryPoint() {
        Map<Long, CameraCount> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT entry_point_id, count(*) AS total, count(*) FILTER (WHERE enabled) AS enabled
                          FROM attend.camera
                         GROUP BY entry_point_id
                        """).query(rs -> {
            counts.put(rs.getLong("entry_point_id"), new CameraCount(rs.getInt("total"), rs.getInt("enabled")));
        });
        return counts;
    }

    /** Audit entries (category Organisation) whose target is "&lt;code&gt; · …", oldest first. */
    public List<AllocationChange> allocationChanges(Collection<String> allocationCodes) {
        if (allocationCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT split_part(target, ' ', 1) AS allocation_code, occurred_at, actor_label, action,
                               before_value, after_value
                          FROM attend.audit_log
                         WHERE category = 'ORGANISATION'
                           AND split_part(target, ' ', 1) IN (:codes)
                         ORDER BY occurred_at, id
                        """)
                .param("codes", allocationCodes)
                .query((rs, n) -> new AllocationChange(
                        rs.getString("allocation_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("actor_label"),
                        rs.getString("action"),
                        rs.getString("before_value"),
                        rs.getString("after_value")))
                .list();
    }

    private static PersonRow person(ResultSet rs, int rowNum) throws SQLException {
        return new PersonRow(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getBoolean("account_enabled"),
                rs.getObject("left_on", LocalDate.class),
                (Long) rs.getObject("department_id"),
                (Long) rs.getObject("location_id"),
                rs.getObject("join_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getString("hrms_employee_id"),
                rs.getString("designation"));
    }
}
