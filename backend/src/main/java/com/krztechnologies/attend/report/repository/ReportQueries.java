package com.krztechnologies.attend.report.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL aggregates of the two reports (read-only use of the employee, organisation, camera, crossing and review
 * tables, D-038): enrolment coverage and recognition quality.
 */
@Repository
public class ReportQueries {

    /**
     * Every enabled employee with the coverage group of the prototype's {@code coverageGroup}: TRACKED, REENROL (tracked
     * but needs re-enrolment), NO_CAMERA, EXEMPT, PENDING (consent pending) or NO_FACE; and whether the camera could
     * cover them at all (a location with an enabled camera, not exempt).
     */
    private static final String COVERAGE = """
            WITH coverage AS (
                SELECT e.id, e.display_name, m.hrms_employee_id, d.code AS department_code, d.name AS department_name,
                       d.status AS department_status, l.name AS location_name,
                       coalesce(f.status, 'NOT_ENROLLED') AS face_status, f.reason AS face_reason,
                       CASE WHEN m.tracking_mode = 'TRACKED'
                                 THEN CASE WHEN f.status = 'NEEDS_REENROL' THEN 'REENROL' ELSE 'TRACKED' END
                            WHEN m.tracking_reason = 'NO_CAMERA' THEN 'NO_CAMERA'
                            WHEN m.tracking_reason = 'EXEMPT' THEN 'EXEMPT'
                            WHEN f.status = 'CONSENT_PENDING' THEN 'PENDING'
                            ELSE 'NO_FACE' END AS grp,
                       m.tracking_mode = 'TRACKED' AS tracked,
                       m.tracking_reason IS DISTINCT FROM 'EXEMPT'
                           AND EXISTS (SELECT 1 FROM attend.camera c JOIN attend.entry_point p ON p.id = c.entry_point_id
                                        WHERE c.enabled AND p.location_id = m.location_id) AS eligible
                  FROM attend.employee e
                  LEFT JOIN attend.employment m ON m.employee_id = e.id
                  LEFT JOIN attend.department d ON d.id = m.department_id
                  LEFT JOIN attend.location l ON l.id = m.location_id
                  LEFT JOIN attend.face_profile f ON f.employee_id = e.id
                 WHERE e.account_enabled)
            """;

    private final JdbcClient jdbc;

    public ReportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Employees the camera could cover, and those of them it does. */
    public record Covered(int eligible, int covered) {}

    public Covered covered() {
        return jdbc.sql(COVERAGE + """
                        SELECT count(*) FILTER (WHERE eligible) AS eligible,
                               count(*) FILTER (WHERE eligible AND tracked) AS covered
                          FROM coverage
                        """)
                .query((rs, n) -> new Covered(rs.getInt("eligible"), rs.getInt("covered")))
                .single();
    }

    /** Enabled employees per coverage group. */
    public Map<String, Integer> groups() {
        Map<String, Integer> groups = new LinkedHashMap<>();
        jdbc.sql(COVERAGE + "SELECT grp, count(*) AS n FROM coverage GROUP BY grp")
                .query((ResultSet rs) -> {
                    groups.put(rs.getString("grp"), rs.getInt("n"));
                });
        return groups;
    }

    /** One active department's employees in one coverage group. */
    public record DepartmentGroup(String code, String name, String group, int count) {}

    public List<DepartmentGroup> departments() {
        return jdbc.sql(COVERAGE + """
                        SELECT department_code, department_name, grp, count(*) AS n
                          FROM coverage
                         WHERE department_status = 'ACTIVE'
                         GROUP BY department_code, department_name, grp
                         ORDER BY lower(department_name), grp
                        """)
                .query((rs, n) -> new DepartmentGroup(
                        rs.getString("department_code"),
                        rs.getString("department_name"),
                        rs.getString("grp"),
                        rs.getInt("n")))
                .list();
    }

    /** The locations with an enabled camera. */
    public List<String> cameraLocations() {
        return jdbc.sql("""
                        SELECT DISTINCT l.name::text AS name FROM attend.location l
                          JOIN attend.entry_point p ON p.location_id = l.id
                          JOIN attend.camera c ON c.entry_point_id = p.id
                         WHERE c.enabled
                         ORDER BY 1
                        """).query(String.class).list();
    }

    /** A department to filter by. */
    public record Department(String code, String name) {}

    /** Every department, the coverage table's filter (as the prototype lists them, archived ones too). */
    public List<Department> allDepartments() {
        return jdbc.sql("SELECT code, name::text AS name FROM attend.department ORDER BY lower(name), code")
                .query((rs, n) -> new Department(rs.getString("code"), rs.getString("name")))
                .list();
    }

    /** The locations of employees without a camera there. */
    public List<String> noCameraLocations() {
        return jdbc.sql(COVERAGE + """
                        SELECT DISTINCT location_name FROM coverage
                         WHERE grp = 'NO_CAMERA' AND location_name IS NOT NULL ORDER BY location_name
                        """).query(String.class).list();
    }

    /** An enabled employee the camera does not cover (fully). */
    public record Uncovered(
            UUID id,
            String name,
            String employeeId,
            String departmentCode,
            String department,
            String location,
            String faceStatus,
            String faceReason,
            String group,
            int templates) {}

    public List<Uncovered> uncovered() {
        return jdbc.sql(COVERAGE + """
                        SELECT c.*, (SELECT count(*) FROM attend.face_template t
                                      WHERE t.employee_id = c.id AND t.active) AS templates
                          FROM coverage c
                         WHERE grp <> 'TRACKED'
                         ORDER BY grp, lower(display_name)
                        """)
                .query((rs, n) -> new Uncovered(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("hrms_employee_id"),
                        rs.getString("department_code"),
                        rs.getString("department_name"),
                        rs.getString("location_name"),
                        rs.getString("face_status"),
                        rs.getString("face_reason"),
                        rs.getString("grp"),
                        rs.getInt("templates")))
                .list();
    }

    /** One IST day of crossings, counted the prototype's ways (U-turns left out except for liveness). */
    public record CrossingDay(
            LocalDate day,
            int counted,
            int ins,
            int outs,
            int faceAuto,
            int reidAuto,
            int inByFace,
            int outByReidAuto,
            int outByReid,
            int reviewed,
            int resolvedInReview,
            int pendingOrReview,
            int notIdentified,
            int livenessFailed,
            int duplicates) {}

    public List<CrossingDay> crossingDays(Instant from, Instant to, String zone) {
        return jdbc.sql("""
                        SELECT (occurred_at AT TIME ZONE :zone)::date AS day,
                               count(*) FILTER (WHERE NOT u_turn) AS counted,
                               count(*) FILTER (WHERE NOT u_turn AND direction = 'IN') AS ins,
                               count(*) FILTER (WHERE NOT u_turn AND direction = 'OUT') AS outs,
                               count(*) FILTER (WHERE NOT u_turn AND method = 'FACE' AND decision = 'AUTO') AS face_auto,
                               count(*) FILTER (WHERE NOT u_turn AND method = 'REID' AND decision = 'AUTO') AS reid_auto,
                               count(*) FILTER (WHERE NOT u_turn AND direction = 'IN' AND method = 'FACE'
                                                  AND decision = 'AUTO') AS in_by_face,
                               count(*) FILTER (WHERE NOT u_turn AND direction = 'OUT' AND method = 'REID'
                                                  AND decision = 'AUTO') AS out_by_reid_auto,
                               count(*) FILTER (WHERE NOT u_turn AND direction = 'OUT' AND method = 'REID') AS out_by_reid,
                               count(*) FILTER (WHERE NOT u_turn
                                                  AND (review_item_id IS NOT NULL OR method = 'REVIEW')) AS reviewed,
                               count(*) FILTER (WHERE NOT u_turn AND method = 'REVIEW') AS resolved_in_review,
                               count(*) FILTER (WHERE NOT u_turn
                                                  AND (method = 'REVIEW' OR decision = 'PENDING_REVIEW')) AS pending_or_review,
                               count(*) FILTER (WHERE NOT u_turn AND employee_id IS NULL
                                                  AND decision <> 'PENDING_REVIEW') AS not_identified,
                               count(*) FILTER (WHERE liveness = 'FAILED') AS liveness_failed,
                               count(*) FILTER (WHERE employee_id IS NOT NULL AND effect = 'IGNORED_DUPLICATE') AS duplicates
                          FROM attend.crossing
                         WHERE occurred_at >= :from AND occurred_at < :to
                         GROUP BY 1
                        """)
                .param("zone", zone)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new CrossingDay(
                        rs.getObject("day", LocalDate.class),
                        rs.getInt("counted"),
                        rs.getInt("ins"),
                        rs.getInt("outs"),
                        rs.getInt("face_auto"),
                        rs.getInt("reid_auto"),
                        rs.getInt("in_by_face"),
                        rs.getInt("out_by_reid_auto"),
                        rs.getInt("out_by_reid"),
                        rs.getInt("reviewed"),
                        rs.getInt("resolved_in_review"),
                        rs.getInt("pending_or_review"),
                        rs.getInt("not_identified"),
                        rs.getInt("liveness_failed"),
                        rs.getInt("duplicates")))
                .list();
    }

    /**
     * One IST day of review items.
     *
     * @param waitMinutes the average time to a person's decision of the items created that day, null without any
     */
    public record ReviewDay(LocalDate day, int unknownFaces, Double waitMinutes) {}

    public List<ReviewDay> reviewDays(Instant from, Instant to, String zone) {
        return jdbc.sql("""
                        SELECT (created_at AT TIME ZONE :zone)::date AS day,
                               count(*) FILTER (WHERE type = 'UNKNOWN') AS unknown_faces,
                               (avg(extract(EPOCH FROM resolved_at - created_at) / 60)
                                    FILTER (WHERE resolved_by IS NOT NULL))::float8 AS wait_minutes
                          FROM attend.review_item
                         WHERE created_at >= :from AND created_at < :to
                         GROUP BY 1
                        """)
                .param("zone", zone)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new ReviewDay(
                        rs.getObject("day", LocalDate.class),
                        rs.getInt("unknown_faces"),
                        rs.getObject("wait_minutes", Double.class)))
                .list();
    }

    /** The first enabled camera's daily uptime (the prototype's report reads its single camera). */
    public Map<LocalDate, BigDecimal> uptime(LocalDate first, LocalDate last) {
        Map<LocalDate, BigDecimal> uptime = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT h.day, h.uptime_pct FROM attend.camera_health_daily h
                         WHERE h.camera_id = (SELECT id FROM attend.camera WHERE enabled ORDER BY code LIMIT 1)
                           AND h.day BETWEEN :first AND :last
                        """).param("first", first).param("last", last).query((ResultSet rs) -> {
            uptime.put(rs.getObject("day", LocalDate.class), rs.getBigDecimal("uptime_pct"));
        });
        return uptime;
    }

    /** Review items created in the period by outcome: the resolution action, or EXPIRED. */
    public Map<String, Integer> decisions(Instant from, Instant to) {
        Map<String, Integer> decisions = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT CASE WHEN status = 'EXPIRED' THEN 'EXPIRED' ELSE resolution_action END AS outcome,
                               count(*) AS n
                          FROM attend.review_item
                         WHERE created_at >= :from AND created_at < :to AND status IN ('RESOLVED', 'EXPIRED')
                         GROUP BY 1
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs) -> {
                    decisions.put(rs.getString("outcome"), rs.getInt("n"));
                });
        return decisions;
    }

    /** The average score of automatic face matches in the period, empty without any. */
    public Optional<BigDecimal> averageFaceScore(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT avg(score) FROM attend.crossing
                         WHERE occurred_at >= :from AND occurred_at < :to AND NOT u_turn
                           AND method = 'FACE' AND decision = 'AUTO'
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(BigDecimal.class)
                .optional();
    }

    /**
     * An employee recognised weakly in the period: automatic face matches averaging under {@code weakBelow}, or three or
     * more crossings resolved in review.
     */
    public record WeakMatch(
            UUID id, String name, String employeeId, String faceStatus, BigDecimal averageScore, int reviews) {}

    public List<WeakMatch> weakMatches(Instant from, Instant to, double weakBelow, int limit) {
        return jdbc.sql("""
                        SELECT c.employee_id, e.display_name, m.hrms_employee_id,
                               coalesce(f.status, 'NOT_ENROLLED') AS face_status,
                               avg(c.score) FILTER (WHERE c.method = 'FACE' AND c.decision = 'AUTO') AS average_score,
                               count(*) FILTER (WHERE c.method = 'REVIEW') AS reviews
                          FROM attend.crossing c
                          JOIN attend.employee e ON e.id = c.employee_id
                          LEFT JOIN attend.employment m ON m.employee_id = c.employee_id
                          LEFT JOIN attend.face_profile f ON f.employee_id = c.employee_id
                         WHERE c.occurred_at >= :from AND c.occurred_at < :to AND NOT c.u_turn
                         GROUP BY c.employee_id, e.display_name, m.hrms_employee_id, f.status
                        HAVING count(*) FILTER (WHERE c.method = 'FACE' AND c.decision = 'AUTO') > 0
                           AND (avg(c.score) FILTER (WHERE c.method = 'FACE' AND c.decision = 'AUTO') < :weak
                                OR count(*) FILTER (WHERE c.method = 'REVIEW') >= 3)
                         ORDER BY average_score, lower(e.display_name)
                         LIMIT :limit
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("weak", weakBelow)
                .param("limit", limit)
                .query(ReportQueries::weakMatch)
                .list();
    }

    /** The active threshold profile's code ("Threshold profile TP-3"). */
    public Optional<String> activeProfile() {
        return jdbc.sql("SELECT code FROM attend.threshold_profile WHERE active")
                .query(String.class)
                .optional();
    }

    private static WeakMatch weakMatch(ResultSet rs, int n) throws SQLException {
        return new WeakMatch(
                rs.getObject("employee_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("hrms_employee_id"),
                rs.getString("face_status"),
                rs.getBigDecimal("average_score"),
                rs.getInt("reviews"));
    }
}
