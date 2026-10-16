package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.employee.domain.Employment;
import com.krztechnologies.attend.employee.domain.Employment.TrackingReason;
import com.krztechnologies.attend.employee.domain.FaceStatus;
import com.krztechnologies.attend.employee.repository.EmployeeQueries;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model of employees for other modules: summaries for lists (the one {@code PersonCell} source: name, work
 * e-mail, HRMS Employee ID, account state; callers decide whether the e-mail may be shown, people.view.sensitive) and
 * the attendance profile (department, location, dates, tracking, face status) for attendance and crossing ingestion.
 */
@Service
public class EmployeeDirectory {

    private final JdbcClient jdbc;

    public EmployeeDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param employeeNumber HRMS Employee ID (EMP-1234), null when missing
     * @param designation and {@code department}: the employment's, null when missing
     */
    public record EmployeeSummary(
            UUID id,
            String displayName,
            String upn,
            String employeeNumber,
            boolean accountEnabled,
            Instant lastSignInAt,
            String designation,
            String department) {}

    /**
     * What attendance (R5–R7) and crossing ingestion need to know about an employee.
     *
     * @param employeeNumber HRMS Employee ID, null when missing
     * @param joinDate null while the employment details are missing
     * @param leftOn the last day of a disabled account, or null
     * @param tracked tracking mode {@code TRACKED} (no employment record = not tracked, no face profile)
     * @param notTrackedReason {@code NO_FACE}, {@code NO_CAMERA} or {@code EXEMPT} when not tracked
     * @param notTrackedLabel the reason's label ("No face profile", …) when not tracked
     * @param faceStatus the face profile status ({@code NOT_ENROLLED} without a profile)
     */
    public record AttendanceProfile(
            UUID id,
            String displayName,
            String employeeNumber,
            Long departmentId,
            Long locationId,
            LocalDate joinDate,
            LocalDate leftOn,
            boolean accountEnabled,
            boolean tracked,
            String notTrackedReason,
            String notTrackedLabel,
            String faceStatus) {

        /** "Tracked by camera" or "Not tracked — No face profile" (the prototype's {@code trackingLabel}). */
        public String trackingLabel() {
            return EmployeeDirectory.trackingLabel(accountEnabled, tracked, notTrackedLabel);
        }

        /** The worker may match this person: the face profile is enrolled or needs re-enrolment. */
        public boolean recognisable() {
            return FaceStatus.valueOf(faceStatus).isActive();
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, AttendanceProfile> attendanceProfiles(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        SELECT e.id, e.display_name, m.hrms_employee_id, m.department_id, m.location_id, m.join_date, e.left_on,
                               e.account_enabled, coalesce(m.tracking_mode, 'NOT_TRACKED') AS tracking_mode,
                               CASE WHEN m.employee_id IS NULL THEN 'NO_FACE' ELSE m.tracking_reason END AS tracking_reason,
                               coalesce(f.status, 'NOT_ENROLLED') AS face_status
                          FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                          LEFT JOIN attend.face_profile f ON f.employee_id = e.id
                         WHERE e.id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    boolean tracked = "TRACKED".equals(rs.getString("tracking_mode"));
                    String reason = tracked ? null : rs.getString("tracking_reason");
                    return new AttendanceProfile(
                            rs.getObject("id", UUID.class),
                            rs.getString("display_name"),
                            rs.getString("hrms_employee_id"),
                            (Long) rs.getObject("department_id"),
                            (Long) rs.getObject("location_id"),
                            rs.getObject("join_date", LocalDate.class),
                            rs.getObject("left_on", LocalDate.class),
                            rs.getBoolean("account_enabled"),
                            tracked,
                            reason,
                            reason == null
                                    ? null
                                    : Employment.TrackingReason.valueOf(reason).label(),
                            rs.getString("face_status"));
                })
                .list()
                .stream()
                .collect(Collectors.toMap(AttendanceProfile::id, Function.identity()));
    }

    /**
     * The prototype's {@code trackingLabel}: "Tracked by camera" or "Not tracked — No face profile"; a disabled account
     * is never tracked, whatever its setting.
     */
    public static String trackingLabel(boolean accountEnabled, boolean tracked, String notTrackedLabel) {
        if (!accountEnabled) {
            return "Not tracked — account disabled";
        }
        return tracked ? "Tracked by camera" : "Not tracked — " + notTrackedLabel;
    }

    /**
     * What a person picker or a card shows about an employee.
     *
     * @param employeeNumber HRMS Employee ID, null when missing
     * @param tracked tracking mode {@code TRACKED}
     * @param location the location's name, null when not set
     * @param locationHasCamera an enabled camera stands at one of the location's entry points (R5)
     */
    public record Card(
            UUID id,
            String displayName,
            String employeeNumber,
            String department,
            String designation,
            String faceStatus,
            boolean accountEnabled,
            boolean tracked,
            String trackingLabel,
            String location,
            boolean locationHasCamera) {}

    @Transactional(readOnly = true)
    public Map<UUID, Card> cards(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        SELECT e.id, e.display_name, m.hrms_employee_id, d.name::text AS department, m.designation,
                               coalesce(f.status, 'NOT_ENROLLED') AS face_status, e.account_enabled,
                               coalesce(m.tracking_mode, 'NOT_TRACKED') = 'TRACKED' AS tracked, m.tracking_reason,
                               l.name::text AS location,
                               EXISTS (SELECT 1 FROM attend.camera c JOIN attend.entry_point p ON p.id = c.entry_point_id
                                        WHERE c.enabled AND p.location_id = m.location_id) AS location_has_camera
                          FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                          LEFT JOIN attend.department d ON d.id = m.department_id
                          LEFT JOIN attend.location l ON l.id = m.location_id
                          LEFT JOIN attend.face_profile f ON f.employee_id = e.id
                         WHERE e.id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    String reason = rs.getString("tracking_reason");
                    return new Card(
                            rs.getObject("id", UUID.class),
                            rs.getString("display_name"),
                            rs.getString("hrms_employee_id"),
                            rs.getString("department"),
                            rs.getString("designation"),
                            rs.getString("face_status"),
                            rs.getBoolean("account_enabled"),
                            rs.getBoolean("tracked"),
                            trackingLabel(
                                    rs.getBoolean("account_enabled"),
                                    rs.getBoolean("tracked"),
                                    TrackingReason.valueOf(reason == null ? "NO_FACE" : reason)
                                            .label()),
                            rs.getString("location"),
                            rs.getBoolean("location_has_camera"));
                })
                .list()
                .stream()
                .collect(Collectors.toMap(Card::id, Function.identity()));
    }

    /** Everyone whose name or Employee ID contains {@code q}, by name (the enrolment wizard's search). */
    @Transactional(readOnly = true)
    public List<UUID> search(String q, int limit) {
        String pattern =
                "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                        SELECT e.id FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                         WHERE e.display_name ILIKE :q ESCAPE '\\' OR m.hrms_employee_id ILIKE :q ESCAPE '\\'
                         ORDER BY lower(e.display_name), e.id
                         LIMIT :limit
                        """)
                .param("q", pattern)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** The status of the employee's current consent record ({@code NONE} without one). */
    @Transactional(readOnly = true)
    public String consentStatus(UUID employeeId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT status FROM attend.consent WHERE employee_id = :id AND is_current), 'NONE')
                        """).param("id", employeeId).query(String.class).single();
    }

    /** The label of a face status code ("Needs re-enrolment"), for other modules' exports. */
    public static String faceStatusLabel(String status) {
        return FaceStatus.valueOf(status).label();
    }

    /** How many employees were enrolled or re-enrolled since {@code since} (the Dashboard's enrolment card). */
    @Transactional(readOnly = true)
    public int enrolledSince(Instant since) {
        return jdbc.sql("SELECT count(*) FROM attend.face_profile WHERE status = 'ENROLLED' AND enrolled_at >= :since")
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** Enabled employees without an HRMS Employee ID (the Employees page's "punches can't reach HRMS"). */
    @Transactional(readOnly = true)
    public int missingEmployeeIds() {
        return jdbc.sql("""
                        SELECT count(*) FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                         WHERE e.account_enabled AND m.hrms_employee_id IS NULL
                        """).query(Integer.class).single();
    }

    /** Enabled employees whose employment details are missing (R16: created by the sync, not edited yet). */
    @Transactional(readOnly = true)
    public int missingEmployment() {
        return jdbc.sql("SELECT count(*) FROM attend.employee e LEFT JOIN attend.employment m ON m.employee_id = e.id"
                        + " WHERE e.account_enabled AND " + EmployeeQueries.MISSING_EMPLOYMENT)
                .query(Integer.class)
                .single();
    }

    /** Enabled employees whose face needs re-enrolment, by name (the Dashboard's alert). */
    @Transactional(readOnly = true)
    public List<String> needingReenrolment() {
        return jdbc.sql("""
                        SELECT e.display_name FROM attend.employee e
                          JOIN attend.face_profile f ON f.employee_id = e.id
                         WHERE e.account_enabled AND f.status = 'NEEDS_REENROL'
                         ORDER BY lower(e.display_name)
                        """).query(String.class).list();
    }

    /** Employees whose attendance may still change on or after {@code from}: enabled, or disabled since then. */
    @Transactional(readOnly = true)
    public List<UUID> attendanceEmployees(LocalDate from) {
        return jdbc.sql("""
                        SELECT id FROM attend.employee
                         WHERE account_enabled OR (left_on IS NOT NULL AND left_on >= :from)
                         ORDER BY id
                        """).param("from", from).query(UUID.class).list();
    }

    @Transactional(readOnly = true)
    public Map<UUID, EmployeeSummary> summaries(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<EmployeeSummary> rows = jdbc.sql("""
                        SELECT e.id, e.display_name, e.upn::text AS upn, m.hrms_employee_id, e.account_enabled, e.last_sign_in_at,
                               m.designation, d.name::text AS department
                          FROM attend.employee e
                          LEFT JOIN attend.employment m ON m.employee_id = e.id
                          LEFT JOIN attend.department d ON d.id = m.department_id
                         WHERE e.id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    OffsetDateTime signIn = rs.getObject("last_sign_in_at", OffsetDateTime.class);
                    return new EmployeeSummary(
                            rs.getObject("id", UUID.class),
                            rs.getString("display_name"),
                            rs.getString("upn"),
                            rs.getString("hrms_employee_id"),
                            rs.getBoolean("account_enabled"),
                            signIn == null ? null : signIn.toInstant(),
                            rs.getString("designation"),
                            rs.getString("department"));
                })
                .list();
        return rows.stream().collect(Collectors.toMap(EmployeeSummary::id, Function.identity()));
    }
}
