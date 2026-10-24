package com.krztechnologies.attend.crossing.repository;

import com.krztechnologies.attend.common.sql.LikePattern;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 * Read-only SQL of the Events page: the crossing list with its filters, and a crossing's detail with the attendance
 * day, the HRMS punches and the audit trail around it (read-only use of those tables, D-038).
 */
@Repository
public class CrossingQueries {

    /** Sort keys of the list and their SQL. */
    public static final Map<String, String> SORTS = Map.of(
            "time", "c.occurred_at",
            "code", "c.code",
            "direction", "c.direction",
            "person", "lower(coalesce(e.display_name, c.unknown_label, 'Unknown face'))",
            "method", "c.method",
            "decision", "c.decision",
            "effect", "c.effect");

    private static final String COLUMNS = """
            SELECT c.id, c.code, c.occurred_at, c.direction, c.original_direction, c.u_turn, c.method, c.employee_id,
                   e.display_name AS employee_name, m.hrms_employee_id, c.decision, c.score, c.reid_score, c.liveness,
                   c.effect, c.effect_reason, c.unknown_label, cam.code AS camera_code, cam.name AS camera_name,
                   c.face_crop_path IS NOT NULL AS has_face_crop, c.body_crop_path IS NOT NULL AS has_body_crop,
                   c.crops_purged_at IS NOT NULL AS crops_purged, c.direction_unsure
            """;

    private static final String FROM = """
              FROM attend.crossing c
              JOIN attend.camera cam ON cam.id = c.camera_id
              LEFT JOIN attend.employee e ON e.id = c.employee_id
              LEFT JOIN attend.employment m ON m.employee_id = c.employee_id
            """;

    private final JdbcClient jdbc;

    public CrossingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The list filters (null or empty lists = any); {@code from} and {@code to} bound the crossing time (IST days,
     * resolved by the caller).
     *
     * @param methods {@code FACE}, {@code REID}, {@code REVIEW} and {@code NONE} (not identified)
     */
    public record Filter(
            Instant from,
            Instant to,
            List<String> cameras,
            List<String> directions,
            List<String> methods,
            List<String> decisions,
            List<String> effects,
            List<UUID> employeeIds,
            String q) {}

    /** A value of a list filter and how many crossings have it. */
    public record ValueCount(String value, String label, int count) {}

    /** The Events filters' options with their counts in a time range. */
    public record Facets(
            List<ValueCount> directions,
            List<ValueCount> methods,
            List<ValueCount> decisions,
            List<ValueCount> effects,
            List<ValueCount> cameras,
            List<ValueCount> employees) {}

    /** One crossing of the list. */
    public record Row(
            long id,
            String code,
            Instant occurredAt,
            String direction,
            String originalDirection,
            boolean uTurn,
            String method,
            UUID employeeId,
            String employeeName,
            String hrmsEmployeeId,
            String decision,
            BigDecimal score,
            BigDecimal reidScore,
            String liveness,
            String effect,
            String effectReason,
            String unknownLabel,
            String cameraCode,
            String cameraName,
            boolean hasFaceCrop,
            boolean hasBodyCrop,
            boolean cropsPurged,
            boolean directionUnsure) {}

    /**
     * What the detail adds to the row.
     *
     * @param faceCrop the encrypted face crop's path (for a template, D-118; never sent to the browser)
     */
    public record Extra(
            String entryPoint,
            String department,
            LocalDate shiftDay,
            String quality,
            Integer trackId,
            String reviewCode,
            String reviewStatus,
            String resolvedBy,
            Instant resolvedAt,
            String flipBy,
            String flipReason,
            String voidBy,
            String voidReason,
            String faceCrop) {}

    /** The attendance day of an identified crossing. */
    public record Day(
            LocalDate shiftDay,
            String shiftCode,
            String shiftName,
            Instant shiftStart,
            Instant shiftEnd,
            String status,
            String statusCode,
            boolean finalised,
            boolean tracked) {}

    /** An HRMS outbox row of the crossing's punches. */
    public record Punch(String punchId, String kind, String type, String status, Instant lastAttemptAt) {}

    /** An audit entry that names the crossing. */
    public record Change(Instant at, String action, String actor, String after) {}

    public List<Row> list(Filter filter, String sortSql, boolean ascending, long offset, int limit) {
        Where where = where(filter);
        String order = sortSql + (ascending ? " ASC NULLS LAST" : " DESC NULLS LAST") + ", c.id DESC";
        var spec = jdbc.sql(COLUMNS + FROM + where.sql() + " ORDER BY " + order + " LIMIT :limit OFFSET :offset");
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.param("limit", limit)
                .param("offset", offset)
                .query(CrossingQueries::row)
                .list();
    }

    public long count(Filter filter) {
        Where where = where(filter);
        var spec = jdbc.sql("SELECT count(*) " + FROM + where.sql());
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.query(Long.class).single();
    }

    public Optional<Row> one(String code) {
        return jdbc.sql(COLUMNS + FROM + " WHERE c.code = :code")
                .param("code", code)
                .query(CrossingQueries::row)
                .optional();
    }

    /** Counts per filter value in [{@code from}, {@code to}); cameras include those without crossings. */
    public Facets facets(Instant from, Instant to) {
        String range = " WHERE c.occurred_at >= :from AND c.occurred_at < :to";
        return new Facets(
                counts(
                        "SELECT c.direction AS value, c.direction AS label, count(*) AS n FROM attend.crossing c"
                                + range + " GROUP BY 1, 2 ORDER BY 1",
                        from,
                        to),
                counts(
                        "SELECT coalesce(c.method, 'NONE') AS value, coalesce(c.method, 'NONE') AS label, count(*) AS n"
                                + " FROM attend.crossing c" + range + " GROUP BY 1, 2 ORDER BY 1",
                        from,
                        to),
                counts(
                        "SELECT c.decision AS value, c.decision AS label, count(*) AS n FROM attend.crossing c" + range
                                + " GROUP BY 1, 2 ORDER BY 1",
                        from,
                        to),
                counts(
                        "SELECT c.effect AS value, c.effect AS label, count(*) AS n FROM attend.crossing c" + range
                                + " AND c.effect IS NOT NULL GROUP BY 1, 2 ORDER BY 1",
                        from,
                        to),
                counts("""
                        SELECT cam.code AS value, cam.name AS label, count(c.id) AS n
                          FROM attend.camera cam
                          LEFT JOIN attend.crossing c
                                 ON c.camera_id = cam.id AND c.occurred_at >= :from AND c.occurred_at < :to
                         GROUP BY 1, 2 ORDER BY 1
                        """, from, to),
                counts("""
                        SELECT e.id::text AS value, e.display_name AS label, count(*) AS n
                          FROM attend.crossing c JOIN attend.employee e ON e.id = c.employee_id
                        """ + range + " GROUP BY 1, 2 ORDER BY lower(e.display_name)", from, to));
    }

    private List<ValueCount> counts(String sql, Instant from, Instant to) {
        return jdbc.sql(sql)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new ValueCount(rs.getString("value"), rs.getString("label"), rs.getInt("n")))
                .list();
    }

    /** Identified crossings of one IST day and hour in one direction. */
    public record HourCount(LocalDate day, int hour, String direction, int count) {}

    /**
     * Identified crossings per local day, hour and direction between {@code from} and {@code to} (U-turns and voided
     * crossings left out), for the Dashboard's chart.
     */
    public List<HourCount> hourly(Instant from, Instant to, String zone) {
        return jdbc.sql("""
                        SELECT (c.occurred_at AT TIME ZONE :zone)::date AS day,
                               extract(hour FROM c.occurred_at AT TIME ZONE :zone)::int AS hour, c.direction,
                               count(*) AS n
                          FROM attend.crossing c
                         WHERE c.occurred_at >= :from AND c.occurred_at < :to AND c.employee_id IS NOT NULL
                           AND NOT c.u_turn AND c.effect IS DISTINCT FROM 'VOIDED'
                         GROUP BY 1, 2, 3
                        """)
                .param("zone", zone)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new HourCount(
                        rs.getObject("day", LocalDate.class),
                        rs.getInt("hour"),
                        rs.getString("direction"),
                        rs.getInt("n")))
                .list();
    }

    /** The crop files of a crossing (relative to {@code crops/}; null when not captured). */
    public record Crops(String face, String faceBlur, String body, String bodyBlur, boolean purged) {}

    public Optional<Crops> crops(String code) {
        return jdbc.sql("""
                        SELECT face_crop_path, face_crop_blur_path, body_crop_path, body_crop_blur_path,
                               crops_purged_at IS NOT NULL AS purged
                          FROM attend.crossing WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, n) -> new Crops(
                        rs.getString("face_crop_path"),
                        rs.getString("face_crop_blur_path"),
                        rs.getString("body_crop_path"),
                        rs.getString("body_crop_blur_path"),
                        rs.getBoolean("purged")))
                .optional();
    }

    public Extra extra(long crossingId) {
        return jdbc.sql("""
                        SELECT ep.name AS entry_point, d.name::text AS department, c.shift_day, c.quality::text AS quality,
                               c.track_id, r.code AS review_code, r.status AS review_status, rb.display_name AS resolved_by,
                               c.resolved_at, fe.display_name AS flip_by, f.reason AS flip_reason,
                               ve.display_name AS void_by, v.reason AS void_reason, c.face_crop_path
                          FROM attend.crossing c
                          JOIN attend.camera cam ON cam.id = c.camera_id
                          JOIN attend.entry_point ep ON ep.id = cam.entry_point_id
                          LEFT JOIN attend.employment m ON m.employee_id = c.employee_id
                          LEFT JOIN attend.department d ON d.id = m.department_id
                          LEFT JOIN attend.review_item r ON r.id = c.review_item_id
                          LEFT JOIN attend.employee rb ON rb.id = c.resolved_by
                          LEFT JOIN LATERAL (
                              SELECT k.reason, k.actor_id FROM attend.correction k
                               WHERE k.crossing_id = c.id AND k.kind = 'FLIP' AND k.undone_at IS NULL
                               ORDER BY k.created_at DESC LIMIT 1) f ON true
                          LEFT JOIN attend.employee fe ON fe.id = f.actor_id
                          LEFT JOIN LATERAL (
                              SELECT k.reason, k.actor_id FROM attend.correction k
                               WHERE k.crossing_id = c.id AND k.kind = 'VOID' AND k.undone_at IS NULL
                               ORDER BY k.created_at DESC LIMIT 1) v ON true
                          LEFT JOIN attend.employee ve ON ve.id = v.actor_id
                         WHERE c.id = :id
                        """)
                .param("id", crossingId)
                .query((rs, n) -> new Extra(
                        rs.getString("entry_point"),
                        rs.getString("department"),
                        rs.getObject("shift_day", LocalDate.class),
                        rs.getString("quality"),
                        (Integer) rs.getObject("track_id"),
                        rs.getString("review_code"),
                        rs.getString("review_status"),
                        rs.getString("resolved_by"),
                        instant(rs, "resolved_at"),
                        rs.getString("flip_by"),
                        rs.getString("flip_reason"),
                        rs.getString("void_by"),
                        rs.getString("void_reason"),
                        rs.getString("face_crop_path")))
                .single();
    }

    public Optional<Day> day(UUID employeeId, LocalDate shiftDay) {
        return jdbc.sql("""
                        SELECT a.shift_day, s.code AS shift_code, s.name::text AS shift_name, a.shift_start, a.shift_end,
                               a.status, a.status_code, a.finalised, a.tracked
                          FROM attend.attendance_day a JOIN attend.shift s ON s.id = a.shift_id
                         WHERE a.employee_id = :employee AND a.shift_day = :day
                        """)
                .param("employee", employeeId)
                .param("day", shiftDay)
                .query((rs, n) -> new Day(
                        rs.getObject("shift_day", LocalDate.class),
                        rs.getString("shift_code"),
                        rs.getString("shift_name"),
                        instant(rs, "shift_start"),
                        instant(rs, "shift_end"),
                        rs.getString("status"),
                        rs.getString("status_code"),
                        rs.getBoolean("finalised"),
                        rs.getBoolean("tracked")))
                .optional();
    }

    /** The outbox rows of {@code PU-<digits>} and {@code PU-<digits>-OUT}, oldest first. */
    public List<Punch> punches(String crossingCode) {
        String punchId = "PU-" + crossingCode.substring(3);
        return jdbc.sql("""
                        SELECT punch_id, kind, payload ->> 'type' AS type, status, last_attempt_at
                          FROM attend.hrms_outbox
                         WHERE punch_id IN (:plain, :out)
                         ORDER BY id
                        """)
                .param("plain", punchId)
                .param("out", punchId + "-OUT")
                .query((rs, n) -> new Punch(
                        rs.getString("punch_id"),
                        rs.getString("kind"),
                        rs.getString("type"),
                        rs.getString("status"),
                        instant(rs, "last_attempt_at")))
                .list();
    }

    /** The latest audit entries whose target names the crossing (corrections, review decisions). */
    public List<Change> history(String crossingCode, int limit) {
        return jdbc.sql("""
                        SELECT occurred_at, action, actor_label, after_value FROM attend.audit_log
                         WHERE target LIKE :pattern AND target !~ :longer
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("pattern", "%" + crossingCode + "%")
                .param("longer", crossingCode + "[0-9]")
                .param("limit", limit)
                .query((rs, n) -> new Change(
                        instant(rs, "occurred_at"),
                        rs.getString("action"),
                        rs.getString("actor_label"),
                        rs.getString("after_value")))
                .list();
    }

    private record Where(String sql, Map<String, Object> params) {}

    private static Where where(Filter f) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        clauses.add("c.occurred_at >= :from AND c.occurred_at < :to");
        params.put("from", Timestamp.from(f.from()));
        params.put("to", Timestamp.from(f.to()));
        any(clauses, params, "cam.code", "cameras", f.cameras());
        any(clauses, params, "c.direction", "directions", f.directions());
        any(clauses, params, "coalesce(c.method, 'NONE')", "methods", f.methods());
        any(clauses, params, "c.decision", "decisions", f.decisions());
        any(clauses, params, "c.effect", "effects", f.effects());
        any(clauses, params, "c.employee_id", "employees", f.employeeIds());
        if (f.q() != null && !f.q().isBlank()) {
            String text = LikePattern.contains(f.q());
            clauses.add("""
                    (lower(c.code) LIKE :q OR lower(coalesce(e.display_name, '')) LIKE :q
                     OR lower(coalesce(m.hrms_employee_id, '')) LIKE :q OR lower(coalesce(c.unknown_label, '')) LIKE :q
                     OR lower(coalesce(c.effect_reason, '')) LIKE :q)""");
            params.put("q", text);
        }
        return new Where(" WHERE " + String.join(" AND ", clauses), params);
    }

    /** {@code column IN (:name)} when the list has values. */
    private static void any(
            List<String> clauses, Map<String, Object> params, String column, String name, List<?> values) {
        if (values != null && !values.isEmpty()) {
            clauses.add(column + " IN (:" + name + ")");
            params.put(name, values);
        }
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getString("code"),
                instant(rs, "occurred_at"),
                rs.getString("direction"),
                rs.getString("original_direction"),
                rs.getBoolean("u_turn"),
                rs.getString("method"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"),
                rs.getString("hrms_employee_id"),
                rs.getString("decision"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("reid_score"),
                rs.getString("liveness"),
                rs.getString("effect"),
                rs.getString("effect_reason"),
                rs.getString("unknown_label"),
                rs.getString("camera_code"),
                rs.getString("camera_name"),
                rs.getBoolean("has_face_crop"),
                rs.getBoolean("has_body_crop"),
                rs.getBoolean("crops_purged"),
                rs.getBoolean("direction_unsure"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
