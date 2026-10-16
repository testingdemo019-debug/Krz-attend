package com.krztechnologies.attend.hrms.repository;

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
 * Read-only SQL of Settings → KRZ HRMS: the outbox list with its filters, the counts per status and the latest payloads
 * as samples (read-only use of the employee tables for names and Employee IDs, D-038).
 */
@Repository
public class OutboxQueries {

    /** Sort keys of the list and their SQL. */
    public static final Map<String, String> SORTS = Map.of(
            "created", "o.created_at",
            "punch", "o.punch_id",
            "employee", "lower(e.display_name)",
            "day", "o.shift_day",
            "status", "o.status",
            "attempts", "o.attempts");

    private static final String SELECT = """
            SELECT o.code, o.kind, o.punch_id, o.payload::text AS payload, o.employee_id, e.display_name,
                   m.hrms_employee_id, o.shift_day, o.status, o.attempts, o.last_error, o.created_at, o.last_attempt_at,
                   o.next_attempt_at, o.sent_at
              FROM attend.hrms_outbox o
              JOIN attend.employee e ON e.id = o.employee_id
              LEFT JOIN attend.employment m ON m.employee_id = o.employee_id
            """;

    private final JdbcClient jdbc;

    public OutboxQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The list filters (all optional).
     *
     * @param statuses any of these statuses
     * @param q OB code, punch ID, employee name, Employee ID or error text
     */
    public record Filter(List<String> statuses, List<String> kinds, LocalDate from, LocalDate to, String q) {}

    /** An outbox entry with its employee. */
    public record Row(
            String code,
            String kind,
            String punchId,
            String payload,
            UUID employeeId,
            String employeeName,
            String hrmsEmployeeId,
            LocalDate shiftDay,
            String status,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant lastAttemptAt,
            Instant nextAttemptAt,
            Instant sentAt) {}

    private record Where(String sql, Map<String, Object> params) {}

    public List<Row> list(Filter filter, String sortSql, boolean ascending, long offset, int limit) {
        Where where = where(filter);
        String order = sortSql + (ascending ? " ASC NULLS LAST" : " DESC NULLS LAST") + ", o.id DESC";
        var spec = jdbc.sql(SELECT + where.sql() + " ORDER BY " + order + " LIMIT :limit OFFSET :offset");
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.param("limit", limit)
                .param("offset", offset)
                .query(OutboxQueries::row)
                .list();
    }

    public long count(Filter filter) {
        Where where = where(filter);
        var spec = jdbc.sql("SELECT count(*) FROM attend.hrms_outbox o"
                + " JOIN attend.employee e ON e.id = o.employee_id"
                + " LEFT JOIN attend.employment m ON m.employee_id = o.employee_id" + where.sql());
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.query(Long.class).single();
    }

    public Optional<Row> one(String code) {
        return jdbc.sql(SELECT + " WHERE o.code = :code")
                .param("code", code)
                .query(OutboxQueries::row)
                .optional();
    }

    /** Entries per status (statuses without entries are missing). */
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM attend.hrms_outbox GROUP BY status")
                .query((ResultSet rs) -> {
                    counts.put(rs.getString("status"), rs.getLong("n"));
                });
        return counts;
    }

    /** The employees with punches blocked for a missing Employee ID, by name. */
    public List<String> blockedEmployees() {
        return jdbc.sql("""
                        SELECT e.display_name FROM attend.employee e
                         WHERE EXISTS (SELECT 1 FROM attend.hrms_outbox o
                                        WHERE o.employee_id = e.id AND o.status = 'BLOCKED_NO_EMPLOYEE_ID')
                         ORDER BY lower(e.display_name)
                        """).query(String.class).list();
    }

    /** The payload of the newest entry of a kind (PUNCH or VOID), as a sample of the format. */
    public Optional<String> latestPayload(String kind) {
        return jdbc.sql("SELECT payload::text FROM attend.hrms_outbox WHERE kind = :kind ORDER BY id DESC LIMIT 1")
                .param("kind", kind)
                .query(String.class)
                .optional();
    }

    private static Where where(Filter f) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            clauses.add("o.status IN (:statuses)");
            params.put("statuses", f.statuses());
        }
        if (f.kinds() != null && !f.kinds().isEmpty()) {
            clauses.add("o.kind IN (:kinds)");
            params.put("kinds", f.kinds());
        }
        if (f.from() != null) {
            clauses.add("o.shift_day >= :from");
            params.put("from", f.from());
        }
        if (f.to() != null) {
            clauses.add("o.shift_day <= :to");
            params.put("to", f.to());
        }
        if (f.q() != null && !f.q().isBlank()) {
            clauses.add("""
                    (lower(o.code) LIKE :q OR lower(o.punch_id) LIKE :q OR lower(e.display_name) LIKE :q
                     OR lower(coalesce(m.hrms_employee_id, '')) LIKE :q OR lower(coalesce(o.last_error, '')) LIKE :q)""");
            params.put("q", LikePattern.contains(f.q()));
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getString("code"),
                rs.getString("kind"),
                rs.getString("punch_id"),
                rs.getString("payload"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("hrms_employee_id"),
                rs.getObject("shift_day", LocalDate.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "last_attempt_at"),
                instant(rs, "next_attempt_at"),
                instant(rs, "sent_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
