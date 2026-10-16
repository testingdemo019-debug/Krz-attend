package com.krztechnologies.attend.audit.repository;

import com.krztechnologies.attend.common.sql.LikePattern;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the append-only audit log (written only by {@code common/audit/AuditService}), newest first. The WHERE clause
 * is assembled from fixed fragments; every value is a bind parameter.
 */
@Repository
public class AuditLogQueries {

    private static final String COLUMNS = """
            code, occurred_at, actor_id, actor_label, actor_roles, host(actor_ip) AS actor_ip, category, action, target,
            before_value, after_value, details::text AS details, trace_id
            """;

    private final JdbcClient jdbc;

    public AuditLogQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The list filters; null or empty means "any".
     *
     * @param actorIds actors by employee ID, or {@code actorLabels}: by the name as recorded (system actors such as
     *     "System" have no ID), case-insensitive; an entry matching either is kept
     * @param to exclusive
     * @param text free text, matched in the actor, action, target, before/after values and the AU code
     */
    public record Filter(
            List<String> categories,
            List<UUID> actorIds,
            List<String> actorLabels,
            Instant from,
            Instant to,
            String text) {}

    /** A filter value and how many entries have it. */
    public record ValueCount(String value, int count) {}

    /** Sortable columns: API key → SQL expression. */
    public static final Map<String, String> SORTS = Map.of(
            "ts", "occurred_at",
            "actor", "lower(actor_label)",
            "category", "category",
            "action", "lower(action)");

    /** One audit entry as stored. */
    public record Row(
            String code,
            Instant occurredAt,
            UUID actorId,
            String actorLabel,
            String actorRoles,
            String actorIp,
            String category,
            String action,
            String target,
            String before,
            String after,
            String details,
            String traceId) {}

    /** @param sort a key of {@link #SORTS}; ties newest first */
    public List<Row> find(Filter filter, String sort, boolean ascending, long offset, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        params.put("offset", offset);
        params.put("limit", limit);
        String order = SORTS.get(sort) + (ascending ? " ASC" : " DESC") + ", occurred_at DESC, id DESC";
        return jdbc.sql("SELECT " + COLUMNS + " FROM attend.audit_log" + where + " ORDER BY " + order
                        + " OFFSET :offset LIMIT :limit")
                .params(params)
                .query(AuditLogQueries::row)
                .list();
    }

    public long count(Filter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        return jdbc.sql("SELECT count(*) FROM attend.audit_log" + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /** Entries per category and per actor name with the date range and text of the filter (the table's counts). */
    public Map<String, List<ValueCount>> facets(Filter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where =
                where(new Filter(List.of(), List.of(), List.of(), filter.from(), filter.to(), filter.text()), params);
        return Map.of(
                "category",
                jdbc.sql("SELECT category AS value, count(*) AS n FROM attend.audit_log" + where + " GROUP BY 1")
                        .params(params)
                        .query((rs, n) -> new ValueCount(rs.getString("value"), rs.getInt("n")))
                        .list(),
                "actor",
                jdbc.sql("SELECT actor_label AS value, count(*) AS n FROM attend.audit_log" + where
                                + " GROUP BY 1 ORDER BY lower(actor_label)")
                        .params(params)
                        .query((rs, n) -> new ValueCount(rs.getString("value"), rs.getInt("n")))
                        .list());
    }

    private static String where(Filter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if (!filter.categories().isEmpty()) {
            where.append(" AND category IN (:categories)");
            params.put("categories", filter.categories());
        }
        if (!filter.actorIds().isEmpty() || !filter.actorLabels().isEmpty()) {
            where.append(" AND (actor_id IN (:actorIds) OR lower(actor_label) IN (:actorLabels))");
            params.put("actorIds", filter.actorIds().isEmpty() ? List.of(new UUID(0, 0)) : filter.actorIds());
            params.put(
                    "actorLabels",
                    filter.actorLabels().isEmpty()
                            ? List.of("")
                            : filter.actorLabels().stream()
                                    .map(l -> l.toLowerCase(Locale.ROOT))
                                    .toList());
        }
        if (filter.from() != null) {
            where.append(" AND occurred_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND occurred_at < :to");
            params.put("to", Timestamp.from(filter.to()));
        }
        if (filter.text() != null) {
            where.append(" AND (code ILIKE :text ESCAPE '\\' OR actor_label ILIKE :text ESCAPE '\\'")
                    .append(" OR action ILIKE :text ESCAPE '\\' OR target ILIKE :text ESCAPE '\\'")
                    .append(" OR before_value ILIKE :text ESCAPE '\\' OR after_value ILIKE :text ESCAPE '\\')");
            params.put("text", LikePattern.contains(filter.text()));
        }
        return where.toString();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getString("code"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_label"),
                rs.getString("actor_roles"),
                rs.getString("actor_ip"),
                rs.getString("category"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("before_value"),
                rs.getString("after_value"),
                rs.getString("details"),
                rs.getString("trace_id"));
    }
}
