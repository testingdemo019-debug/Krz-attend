package com.krztechnologies.attend.review.repository;

import com.krztechnologies.attend.common.sql.LikePattern;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Review items (R12): the queue, one item locked for a decision, the resolution and its undo, NR-03 waiting items. */
@Repository
public class ReviewStore {

    private static final String COLUMNS = """
            SELECT r.id, r.code, r.type, r.status, r.label, r.unknown_cluster, r.created_at, r.last_sighting_at,
                   r.resolution_action, r.resolution_employee_id, re.display_name AS resolution_employee_name,
                   r.resolved_by, rb.display_name AS resolved_by_name, r.resolved_at, r.resolution_reason,
                   r.resolution_note, r.template_added_id, r.undo_snapshot::text AS undo_snapshot, r.undo_until, r.version
              FROM attend.review_item r
              LEFT JOIN attend.employee re ON re.id = r.resolution_employee_id
              LEFT JOIN attend.employee rb ON rb.id = r.resolved_by
            """;

    private final JdbcClient jdbc;

    public ReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A review item with its resolution. */
    public record Item(
            long id,
            String code,
            String type,
            String status,
            String label,
            String unknownCluster,
            Instant createdAt,
            Instant lastSightingAt,
            String resolutionAction,
            UUID resolutionEmployeeId,
            String resolutionEmployeeName,
            UUID resolvedBy,
            String resolvedByName,
            Instant resolvedAt,
            String resolutionReason,
            String resolutionNote,
            Long templateAddedId,
            String undoSnapshot,
            Instant undoUntil,
            int version) {}

    /** Sort keys of the Resolved tab and their SQL. */
    public static final Map<String, String> SORTS = Map.of(
            "code", "r.id",
            "seen", "r.created_at",
            "resolution", "coalesce(r.resolution_action, r.status)",
            "resolvedBy", "lower(coalesce(rb.display_name, 'System'))",
            "resolvedAt", "coalesce(r.resolved_at, r.last_sighting_at)");

    /**
     * The list filters (null or empty = any); {@code from}/{@code to} bound the creation time.
     *
     * @param status {@code OPEN}, {@code RESOLVED}, {@code EXPIRED} or {@code CLOSED} (not open)
     * @param actions decisions, and {@code EXPIRED} for expired items
     * @param resolvedBy reviewers; {@code systemResolved} adds the items the system closed (expired ones)
     */
    public record Filter(
            List<String> types,
            String status,
            Instant from,
            Instant to,
            String q,
            List<String> actions,
            List<UUID> resolvedBy,
            boolean systemResolved) {}

    /** How many items no longer open have a value: per type, per resolution, per reviewer (null = the system). */
    public record Facets(Map<String, Integer> types, Map<String, Integer> actions, List<ResolverCount> resolvers) {}

    public record ResolverCount(UUID id, String name, int count) {}

    /** A resolution as it is stored. */
    public record Resolution(
            String action,
            UUID employeeId,
            UUID resolvedBy,
            Instant resolvedAt,
            String reason,
            String note,
            Long templateId,
            String undoSnapshot,
            Instant undoUntil) {}

    public Optional<Item> find(String code) {
        return jdbc.sql(COLUMNS + " WHERE r.code = :code")
                .param("code", code)
                .query(ReviewStore::item)
                .optional();
    }

    /** The item, locked until the transaction ends (one decision at a time). */
    public Optional<Item> lock(String code) {
        return jdbc.sql(COLUMNS + " WHERE r.code = :code FOR UPDATE OF r")
                .param("code", code)
                .query(ReviewStore::item)
                .optional();
    }

    /**
     * Open items oldest first; the others by {@code sortSql} (one of {@link #SORTS}).
     *
     * @param limit null = all
     */
    public List<Item> list(Filter f, String sortSql, boolean ascending, long offset, Integer limit) {
        Where where = where(f);
        String order = "OPEN".equals(f.status())
                ? " ORDER BY r.created_at, r.id"
                : " ORDER BY " + sortSql + (ascending ? " ASC NULLS LAST" : " DESC NULLS LAST") + ", r.id DESC";
        var spec = jdbc.sql(COLUMNS + where.sql() + order + (limit == null ? "" : " LIMIT :limit OFFSET :offset"));
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        if (limit != null) {
            spec = spec.param("limit", limit).param("offset", offset);
        }
        return spec.query(ReviewStore::item).list();
    }

    public long count(Filter f) {
        Where where = where(f);
        var spec = jdbc.sql("SELECT count(*) FROM attend.review_item r"
                + " LEFT JOIN attend.employee re ON re.id = r.resolution_employee_id"
                + " LEFT JOIN attend.employee rb ON rb.id = r.resolved_by" + where.sql());
        for (var param : where.params().entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        return spec.query(Long.class).single();
    }

    /**
     * The Dashboard's numbers.
     *
     * @param unknownToday unknown-face items created since the start of today
     * @param waiting open items created before the waiting threshold; {@code oldestWaiting} the first of them
     */
    public record Glance(int unknownToday, int waiting, Instant oldestWaiting) {}

    public Glance glance(Instant startOfToday, Instant waitingBefore) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE type = 'UNKNOWN' AND created_at >= :today) AS unknown_today,
                               count(*) FILTER (WHERE status = 'OPEN' AND created_at < :before) AS waiting,
                               min(created_at) FILTER (WHERE status = 'OPEN' AND created_at < :before) AS oldest
                          FROM attend.review_item
                        """)
                .param("today", Timestamp.from(startOfToday))
                .param("before", Timestamp.from(waitingBefore))
                .query((rs, n) -> {
                    OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
                    return new Glance(
                            rs.getInt("unknown_today"),
                            rs.getInt("waiting"),
                            oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    /** The Resolved tab's filter options: counts over every item no longer open. */
    public Facets facets() {
        Map<String, Integer> types = new LinkedHashMap<>();
        Map<String, Integer> actions = new LinkedHashMap<>();
        List<ResolverCount> resolvers = new ArrayList<>();
        jdbc.sql(
                        "SELECT type, count(*) AS n FROM attend.review_item WHERE status <> 'OPEN' GROUP BY type ORDER BY type")
                .query(rs -> {
                    types.put(rs.getString("type"), rs.getInt("n"));
                });
        jdbc.sql("""
                        SELECT coalesce(resolution_action, status) AS outcome, count(*) AS n FROM attend.review_item
                         WHERE status <> 'OPEN' GROUP BY 1 ORDER BY 1
                        """).query(rs -> {
            actions.put(rs.getString("outcome"), rs.getInt("n"));
        });
        jdbc.sql("""
                        SELECT r.resolved_by, coalesce(rb.display_name, 'System') AS name, count(*) AS n
                          FROM attend.review_item r LEFT JOIN attend.employee rb ON rb.id = r.resolved_by
                         WHERE r.status <> 'OPEN'
                         GROUP BY r.resolved_by, rb.display_name ORDER BY lower(coalesce(rb.display_name, 'System'))
                        """).query(rs -> {
            resolvers.add(
                    new ResolverCount(rs.getObject("resolved_by", UUID.class), rs.getString("name"), rs.getInt("n")));
        });
        return new Facets(types, actions, resolvers);
    }

    /** Open items per type, and everything no longer open. */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT CASE WHEN status = 'OPEN' THEN type ELSE 'RESOLVED' END AS bucket, count(*) AS n
                          FROM attend.review_item GROUP BY 1
                        """).query(rs -> {
            counts.put(rs.getString("bucket"), rs.getInt("n"));
        });
        return counts;
    }

    public void resolve(long itemId, Resolution r) {
        jdbc.sql("""
                        UPDATE attend.review_item
                           SET status = 'RESOLVED', resolution_action = :action, resolution_employee_id = :employee,
                               resolved_by = :by, resolved_at = :at, resolution_reason = :reason, resolution_note = :note,
                               template_added_id = :template, undo_snapshot = CAST(:snapshot AS jsonb),
                               undo_until = :until, version = version + 1
                         WHERE id = :id
                        """)
                .param("action", r.action())
                .param("employee", r.employeeId())
                .param("by", r.resolvedBy())
                .param("at", Timestamp.from(r.resolvedAt()))
                .param("reason", r.reason())
                .param("note", r.note())
                .param("template", r.templateId())
                .param("snapshot", r.undoSnapshot())
                .param("until", Timestamp.from(r.undoUntil()))
                .param("id", itemId)
                .update();
    }

    /** Back in the queue (undo). */
    public void reopen(long itemId) {
        jdbc.sql("""
                        UPDATE attend.review_item
                           SET status = 'OPEN', resolution_action = NULL, resolution_employee_id = NULL,
                               resolved_by = NULL, resolved_at = NULL, resolution_reason = NULL, resolution_note = NULL,
                               template_added_id = NULL, undo_snapshot = NULL, undo_until = NULL, version = version + 1
                         WHERE id = :id
                        """).param("id", itemId).update();
    }

    /** Open items created before {@code before} that have not raised NR-03 yet. */
    public List<Item> waitingSince(Instant before) {
        return jdbc.sql(COLUMNS + " WHERE r.status = 'OPEN' AND r.wait_notified_at IS NULL AND r.created_at < :before"
                        + " ORDER BY r.created_at")
                .param("before", Timestamp.from(before))
                .query(ReviewStore::item)
                .list();
    }

    public void markWaitNotified(long itemId, Instant at) {
        jdbc.sql("UPDATE attend.review_item SET wait_notified_at = :at WHERE id = :id")
                .param("at", Timestamp.from(at))
                .param("id", itemId)
                .update();
    }

    private record Where(String sql, Map<String, Object> params) {}

    private static Where where(Filter f) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if ("CLOSED".equals(f.status())) {
            clauses.add("r.status <> 'OPEN'");
        } else if (f.status() != null) {
            clauses.add("r.status = :status");
            params.put("status", f.status());
        }
        if (f.types() != null && !f.types().isEmpty()) {
            clauses.add("r.type IN (:types)");
            params.put("types", f.types());
        }
        if (f.actions() != null && !f.actions().isEmpty()) {
            clauses.add("coalesce(r.resolution_action, r.status) IN (:actions)");
            params.put("actions", f.actions());
        }
        boolean people = f.resolvedBy() != null && !f.resolvedBy().isEmpty();
        if (people || f.systemResolved()) {
            List<String> who = new ArrayList<>();
            if (people) {
                who.add("r.resolved_by IN (:resolvedBy)");
                params.put("resolvedBy", f.resolvedBy());
            }
            if (f.systemResolved()) {
                who.add("(r.resolved_by IS NULL AND r.status <> 'OPEN')");
            }
            clauses.add("(" + String.join(" OR ", who) + ")");
        }
        if (f.from() != null) {
            clauses.add("r.created_at >= :from");
            params.put("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            clauses.add("r.created_at < :to");
            params.put("to", Timestamp.from(f.to()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String text = LikePattern.contains(f.q());
            clauses.add("""
                    (lower(r.code) LIKE :q OR lower(coalesce(r.label, '')) LIKE :q
                     OR lower(coalesce(re.display_name, '')) LIKE :q OR lower(coalesce(rb.display_name, '')) LIKE :q)""");
            params.put("q", text);
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        Object template = rs.getObject("template_added_id");
        return new Item(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("label"),
                rs.getString("unknown_cluster"),
                instant(rs, "created_at"),
                instant(rs, "last_sighting_at"),
                rs.getString("resolution_action"),
                rs.getObject("resolution_employee_id", UUID.class),
                rs.getString("resolution_employee_name"),
                rs.getObject("resolved_by", UUID.class),
                rs.getString("resolved_by_name"),
                instant(rs, "resolved_at"),
                rs.getString("resolution_reason"),
                rs.getString("resolution_note"),
                template == null ? null : ((Number) template).longValue(),
                rs.getString("undo_snapshot"),
                instant(rs, "undo_until"),
                rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
