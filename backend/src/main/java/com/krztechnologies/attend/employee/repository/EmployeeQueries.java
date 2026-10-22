package com.krztechnologies.attend.employee.repository;

import com.krztechnologies.attend.common.sql.LikePattern;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for Employees (list, profile, files). Department and location names are joined here instead of being
 * fetched one by one (read-only use of the organisation tables, D-038); the attendance days come from
 * {@code attendance_day}, which the attendance module maintains.
 */
@Repository
public class EmployeeQueries {

    private static final String FROM = """
              FROM attend.employee e
              LEFT JOIN attend.employment m ON m.employee_id = e.id
              LEFT JOIN attend.department d ON d.id = m.department_id
              LEFT JOIN attend.location l ON l.id = m.location_id
              LEFT JOIN attend.face_profile f ON f.employee_id = e.id
              LEFT JOIN attend.consent c ON c.employee_id = e.id AND c.is_current
            """;

    private static final String COLUMNS = """
            SELECT e.id, e.display_name, e.upn::text AS upn, e.mail::text AS mail, e.account_enabled, e.disabled_reason,
                   e.left_on, e.last_sign_in_at, e.synced_at, e.entra_object_id, e.entra_tenant_id,
                   m.employee_id IS NOT NULL AS has_employment, m.hrms_employee_id, m.department_id,
                   d.code AS department_code, d.name::text AS department_name, m.designation, m.employment_type,
                   m.location_id, l.code AS location_code, l.name::text AS location_name, m.manager_id, m.join_date,
                   m.end_date, m.tracking_mode, m.tracking_reason, coalesce(m.version, 0) AS employment_version,
                   coalesce(f.status, 'NOT_ENROLLED') AS face_status, f.reason AS face_reason,
                   f.scheduled_erasure_on, f.enrolled_at, coalesce(c.status, 'NONE') AS consent_status,
                   EXISTS (SELECT 1 FROM attend.user_role ur WHERE ur.employee_id = e.id) AS has_access
            """;

    /** "Employment details missing": no record yet, or no designation, location or join date (R16). */
    public static final String MISSING_EMPLOYMENT =
            "(m.employee_id IS NULL OR m.designation IS NULL OR m.location_id IS NULL OR m.join_date IS NULL)";

    private final JdbcClient jdbc;

    public EmployeeQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The list filters (all optional; null or empty lists = any).
     *
     * @param faces face statuses, any of
     * @param trackings {@code TRACKED} and tracking reasons, any of
     * @param employeeIds {@code has} and/or {@code missing} (an HRMS Employee ID)
     * @param accounts {@code enabled} and/or {@code disabled}
     * @param searchEmail whether {@code q} may match the work e-mail (people.view.sensitive)
     */
    public record Filter(
            String quick,
            List<String> departmentCodes,
            List<String> locationCodes,
            List<String> faces,
            List<String> trackings,
            List<String> employmentTypes,
            List<String> employeeIds,
            List<String> accounts,
            String q,
            boolean searchEmail) {}

    /** One employee with employment, face and consent status. */
    public record Row(
            UUID id,
            String displayName,
            String upn,
            String mail,
            boolean accountEnabled,
            String disabledReason,
            LocalDate leftOn,
            Instant lastSignInAt,
            Instant syncedAt,
            UUID objectId,
            UUID tenantId,
            boolean hasEmployment,
            String hrmsEmployeeId,
            Long departmentId,
            String departmentCode,
            String departmentName,
            String designation,
            String employmentType,
            Long locationId,
            String locationCode,
            String locationName,
            UUID managerId,
            LocalDate joinDate,
            LocalDate endDate,
            String trackingMode,
            String trackingReason,
            int employmentVersion,
            String faceStatus,
            String faceReason,
            LocalDate scheduledErasureOn,
            Instant enrolledAt,
            String consentStatus,
            boolean hasAccess) {}

    /** A face template (never the embedding). */
    public record TemplateRow(
            long id,
            String pose,
            Integer quality,
            String source,
            String light,
            Instant createdAt,
            boolean hasThumbnail) {}

    /** The current consent record. */
    public record ConsentRow(
            String status,
            String method,
            Integer noticeVersion,
            String recordedBy,
            Instant recordedAt,
            LocalDate givenOn,
            LocalDate withdrawnOn,
            String documentMime) {}

    /** One attendance day (the profile's "Last 14 days"). */
    public record DayRow(
            LocalDate day,
            String shiftCode,
            String shiftName,
            String status,
            String statusCode,
            Instant checkIn,
            Instant checkOut,
            Integer workedMinutes,
            boolean late,
            boolean finalised,
            boolean checkOutFinal,
            boolean missingCheckOut) {}

    /** One entry of the profile's activity: face-profile history and audit entries about the person. */
    public record HistoryRow(Instant at, String action, String by, String before, String after, String detail) {}

    /** A stored file and its type. */
    public record StoredFile(String path, String mime) {}

    /** Every employee of the filter, by name (the list is sorted and paged by the service). */
    public List<Row> list(Filter filter) {
        Where where = where(filter);
        var spec = jdbc.sql(COLUMNS + FROM + where.sql() + " ORDER BY lower(e.display_name), e.id");
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.query(EmployeeQueries::row).list();
    }

    public Optional<Row> one(UUID id) {
        return jdbc.sql(COLUMNS + FROM + " WHERE e.id = :id")
                .param("id", id)
                .query(EmployeeQueries::row)
                .optional();
    }

    public Optional<String> displayName(UUID id) {
        return jdbc.sql("SELECT display_name FROM attend.employee WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public List<TemplateRow> templates(UUID employeeId) {
        return jdbc.sql("""
                        SELECT id, pose, quality, source, light, created_at, thumbnail_path IS NOT NULL AS has_thumbnail
                          FROM attend.face_template
                         WHERE employee_id = :id AND active
                         ORDER BY created_at, id
                        """)
                .param("id", employeeId)
                .query((rs, n) -> new TemplateRow(
                        rs.getLong("id"),
                        rs.getString("pose"),
                        (Integer) rs.getObject("quality"),
                        rs.getString("source"),
                        rs.getString("light"),
                        instant(rs, "created_at"),
                        rs.getBoolean("has_thumbnail")))
                .list();
    }

    public int activeTemplateCount(UUID employeeId) {
        return jdbc.sql("SELECT count(*) FROM attend.face_template WHERE employee_id = :id AND active")
                .param("id", employeeId)
                .query(Integer.class)
                .single();
    }

    public Optional<ConsentRow> consent(UUID employeeId) {
        return jdbc.sql("""
                        SELECT status, method, notice_version, recorded_by_label, recorded_at, given_on, withdrawn_on,
                               CASE WHEN document_path IS NOT NULL THEN document_mime END AS document_mime
                          FROM attend.consent
                         WHERE employee_id = :id AND is_current
                        """)
                .param("id", employeeId)
                .query((rs, n) -> new ConsentRow(
                        rs.getString("status"),
                        rs.getString("method"),
                        (Integer) rs.getObject("notice_version"),
                        rs.getString("recorded_by_label"),
                        instant(rs, "recorded_at"),
                        rs.getObject("given_on", LocalDate.class),
                        rs.getObject("withdrawn_on", LocalDate.class),
                        rs.getString("document_mime")))
                .optional();
    }

    public Optional<StoredFile> consentDocument(UUID employeeId) {
        return jdbc.sql("""
                        SELECT document_path, document_mime FROM attend.consent
                         WHERE employee_id = :id AND is_current AND document_path IS NOT NULL
                        """)
                .param("id", employeeId)
                .query((rs, n) -> new StoredFile(rs.getString("document_path"), rs.getString("document_mime")))
                .optional();
    }

    public Optional<StoredFile> thumbnail(UUID employeeId, long templateId) {
        return jdbc.sql("""
                        SELECT thumbnail_path FROM attend.face_template
                         WHERE id = :template AND employee_id = :id AND active AND thumbnail_path IS NOT NULL
                        """)
                .param("template", templateId)
                .param("id", employeeId)
                .query((rs, n) -> new StoredFile(rs.getString("thumbnail_path"), "image/jpeg"))
                .optional();
    }

    public List<DayRow> days(UUID employeeId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT a.shift_day, s.code AS shift_code, s.name::text AS shift_name, a.status, a.status_code,
                               ci.occurred_at AS check_in, co.occurred_at AS check_out, a.worked_minutes, a.late,
                               a.finalised, a.check_out_final, a.missing_check_out
                          FROM attend.attendance_day a
                          JOIN attend.shift s ON s.id = a.shift_id
                          LEFT JOIN attend.crossing ci ON ci.id = a.check_in_crossing_id
                          LEFT JOIN attend.crossing co ON co.id = a.check_out_crossing_id
                         WHERE a.employee_id = :id AND a.shift_day BETWEEN :from AND :to AND a.status IS NOT NULL
                         ORDER BY a.shift_day DESC
                        """)
                .param("id", employeeId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new DayRow(
                        rs.getObject("shift_day", LocalDate.class),
                        rs.getString("shift_code"),
                        rs.getString("shift_name"),
                        rs.getString("status"),
                        rs.getString("status_code"),
                        instant(rs, "check_in"),
                        instant(rs, "check_out"),
                        (Integer) rs.getObject("worked_minutes"),
                        rs.getBoolean("late"),
                        rs.getBoolean("finalised"),
                        rs.getBoolean("check_out_final"),
                        rs.getBoolean("missing_check_out")))
                .list();
    }

    /** Face-profile history and the latest audit entries about this person (not review decisions), newest first. */
    public List<HistoryRow> history(UUID employeeId, String displayName, int limit) {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT occurred_at AS at, action, actor_label AS by, NULL AS before_value, NULL AS after_value,
                                   detail
                              FROM attend.face_profile_history WHERE employee_id = :id
                            UNION ALL
                            SELECT occurred_at, action, actor_label, before_value, after_value, NULL
                              FROM attend.audit_log
                             WHERE target = :name AND category IN ('EMPLOYEES', 'USERS_ROLES', 'PRIVACY', 'ENROLMENT', 'SIGN_IN')
                        ) h
                         ORDER BY at DESC
                         LIMIT :limit
                        """)
                .param("id", employeeId)
                .param("name", displayName)
                .param("limit", limit)
                .query((rs, n) -> new HistoryRow(
                        instant(rs, "at"),
                        rs.getString("action"),
                        rs.getString("by"),
                        rs.getString("before_value"),
                        rs.getString("after_value"),
                        rs.getString("detail")))
                .list();
    }

    private record Where(String sql, Map<String, Object> params) {}

    private static Where where(Filter f) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.quick() != null) {
            switch (f.quick()) {
                case "missing-id" -> clauses.add("e.account_enabled AND m.hrms_employee_id IS NULL");
                case "needs-reenrol" -> clauses.add("f.status = 'NEEDS_REENROL'");
                case "not-enrolled" ->
                    clauses.add("coalesce(f.status, 'NOT_ENROLLED') IN ('NOT_ENROLLED', 'CONSENT_PENDING')");
                case "missing-employment" -> clauses.add(MISSING_EMPLOYMENT);
                default -> throw new IllegalArgumentException("Unknown filter " + f.quick());
            }
        }
        any(clauses, params, "d.code", "departments", f.departmentCodes());
        any(clauses, params, "l.code", "locations", f.locationCodes());
        any(clauses, params, "coalesce(f.status, 'NOT_ENROLLED')", "faces", f.faces());
        any(
                clauses,
                params,
                "CASE WHEN m.tracking_mode = 'TRACKED' THEN 'TRACKED' ELSE coalesce(m.tracking_reason, 'NO_FACE') END",
                "trackings",
                f.trackings());
        any(clauses, params, "m.employment_type", "types", f.employmentTypes());
        any(
                clauses,
                params,
                "CASE WHEN m.hrms_employee_id IS NULL THEN 'missing' ELSE 'has' END",
                "employeeIds",
                f.employeeIds());
        any(
                clauses,
                params,
                "CASE WHEN e.account_enabled THEN 'enabled' ELSE 'disabled' END",
                "accounts",
                f.accounts());
        if (f.q() != null && !f.q().isBlank()) {
            String text = LikePattern.contains(f.q());
            String email = f.searchEmail()
                    ? " OR lower(e.upn::text) LIKE :q OR lower(coalesce(e.mail::text, '')) LIKE :q"
                    : "";
            clauses.add("(lower(e.display_name) LIKE :q OR lower(coalesce(m.hrms_employee_id, '')) LIKE :q"
                    + " OR lower(coalesce(m.designation, '')) LIKE :q OR lower(coalesce(d.name::text, '')) LIKE :q"
                    + email + ")");
            params.put("q", text);
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    /** {@code expression IN (:name)} when the list has values. */
    private static void any(
            List<String> clauses, Map<String, Object> params, String expression, String name, List<String> values) {
        if (values != null && !values.isEmpty()) {
            clauses.add(expression + " IN (:" + name + ")");
            params.put(name, values);
        }
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getString("upn"),
                rs.getString("mail"),
                rs.getBoolean("account_enabled"),
                rs.getString("disabled_reason"),
                rs.getObject("left_on", LocalDate.class),
                instant(rs, "last_sign_in_at"),
                instant(rs, "synced_at"),
                rs.getObject("entra_object_id", UUID.class),
                rs.getObject("entra_tenant_id", UUID.class),
                rs.getBoolean("has_employment"),
                rs.getString("hrms_employee_id"),
                (Long) rs.getObject("department_id"),
                rs.getString("department_code"),
                rs.getString("department_name"),
                rs.getString("designation"),
                rs.getString("employment_type"),
                (Long) rs.getObject("location_id"),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getObject("manager_id", UUID.class),
                rs.getObject("join_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getString("tracking_mode"),
                rs.getString("tracking_reason"),
                rs.getInt("employment_version"),
                rs.getString("face_status"),
                rs.getString("face_reason"),
                rs.getObject("scheduled_erasure_on", LocalDate.class),
                instant(rs, "enrolled_at"),
                rs.getString("consent_status"),
                rs.getBoolean("has_access"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
