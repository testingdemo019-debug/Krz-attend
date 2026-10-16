package com.krztechnologies.attend.crossing.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The crossings of a review item and the columns a review decision changes (R12). */
@Repository
public class DecisionStore {

    private final JdbcClient jdbc;

    public DecisionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A crossing of a review item, with what a decision changes and what review shows. */
    public record Row(
            long id,
            long reviewItemId,
            String code,
            UUID crossingUid,
            Instant occurredAt,
            String direction,
            boolean uTurn,
            UUID employeeId,
            String method,
            LocalDate shiftDay,
            String decision,
            UUID resolvedBy,
            Instant resolvedAt,
            String effect,
            String effectReason,
            String quality,
            String candidates,
            String faceCrop,
            boolean cropsPurged,
            String cameraCode,
            String cameraName,
            Integer trackId,
            String bodyCrop,
            String liveness,
            BigDecimal margin,
            BigDecimal reidScore) {}

    private static final String COLUMNS = """
            SELECT c.id, c.review_item_id, c.code, c.crossing_uid, c.occurred_at, c.direction, c.u_turn, c.employee_id,
                   c.method, c.shift_day, c.decision, c.resolved_by, c.resolved_at, c.effect, c.effect_reason,
                   c.quality::text AS quality, c.candidates::text AS candidates, c.face_crop_path,
                   c.crops_purged_at IS NOT NULL AS crops_purged, cam.code AS camera_code, cam.name AS camera_name,
                   c.track_id, c.body_crop_path, c.liveness, c.margin, c.reid_score
              FROM attend.crossing c JOIN attend.camera cam ON cam.id = c.camera_id
            """;

    /** An employee's IN crossing (a back-view exit's "Came in"). */
    public record InCrossing(String code, Instant occurredAt, boolean faceCrop) {}

    /** The item's crossings in time order, locked for the transaction. */
    public List<Row> ofItem(long reviewItemId) {
        return jdbc.sql(COLUMNS + " WHERE c.review_item_id = :item ORDER BY c.occurred_at, c.id FOR UPDATE OF c")
                .param("item", reviewItemId)
                .query(DecisionStore::row)
                .list();
    }

    /** The crossings of these items in time order (the queue; no lock). */
    public List<Row> ofItems(Collection<Long> reviewItemIds) {
        if (reviewItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(COLUMNS + " WHERE c.review_item_id IN (:items) ORDER BY c.occurred_at, c.id")
                .param("items", reviewItemIds)
                .query(DecisionStore::row)
                .list();
    }

    /** The employee's last IN crossing in [{@code since}, {@code before}), not voided. */
    public Optional<InCrossing> lastIn(UUID employeeId, Instant since, Instant before) {
        return jdbc.sql("""
                        SELECT code, occurred_at, face_crop_path IS NOT NULL AND crops_purged_at IS NULL AS face_crop
                          FROM attend.crossing
                         WHERE employee_id = :employee AND direction = 'IN' AND NOT voided
                           AND occurred_at >= :since AND occurred_at < :before
                         ORDER BY occurred_at DESC LIMIT 1
                        """)
                .param("employee", employeeId)
                .param("since", Timestamp.from(since))
                .param("before", Timestamp.from(before))
                .query((rs, n) ->
                        new InCrossing(rs.getString("code"), instant(rs, "occurred_at"), rs.getBoolean("face_crop")))
                .optional();
    }

    /** The crossing now belongs to the employee (method REVIEW). */
    public void identify(long crossingId, UUID employeeId, LocalDate shiftDay, UUID by, Instant at) {
        jdbc.sql("""
                        UPDATE attend.crossing
                           SET employee_id = :employee, method = 'REVIEW', shift_day = :day,
                               decision = 'RESOLVED_IN_REVIEW', resolved_by = :by, resolved_at = :at,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("employee", employeeId)
                .param("day", shiftDay)
                .param("by", by)
                .param("at", Timestamp.from(at))
                .param("id", crossingId)
                .update();
    }

    /** No identity; the decision and its effect. */
    public void decide(long crossingId, String decision, UUID by, Instant at, String effect, String reason) {
        jdbc.sql("""
                        UPDATE attend.crossing
                           SET employee_id = NULL, method = NULL, shift_day = NULL, decision = :decision,
                               resolved_by = :by, resolved_at = :at, effect = :effect, effect_reason = :reason,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("decision", decision)
                .param("by", by)
                .param("at", Timestamp.from(at))
                .param("effect", effect)
                .param("reason", reason)
                .param("id", crossingId)
                .update();
    }

    /** Puts back what a decision changed (undo). */
    public void restore(Row before) {
        jdbc.sql("""
                        UPDATE attend.crossing
                           SET employee_id = :employee, method = :method, shift_day = :day, decision = :decision,
                               resolved_by = :by, resolved_at = :at, effect = :effect, effect_reason = :reason,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("employee", before.employeeId())
                .param("method", before.method())
                .param("day", before.shiftDay())
                .param("decision", before.decision())
                .param("by", before.resolvedBy())
                .param("at", before.resolvedAt() == null ? null : Timestamp.from(before.resolvedAt()))
                .param("effect", before.effect())
                .param("reason", before.effectReason())
                .param("id", before.id())
                .update();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getLong("review_item_id"),
                rs.getString("code"),
                rs.getObject("crossing_uid", UUID.class),
                instant(rs, "occurred_at"),
                rs.getString("direction"),
                rs.getBoolean("u_turn"),
                rs.getObject("employee_id", UUID.class),
                rs.getString("method"),
                rs.getObject("shift_day", LocalDate.class),
                rs.getString("decision"),
                rs.getObject("resolved_by", UUID.class),
                instant(rs, "resolved_at"),
                rs.getString("effect"),
                rs.getString("effect_reason"),
                rs.getString("quality"),
                rs.getString("candidates"),
                rs.getString("face_crop_path"),
                rs.getBoolean("crops_purged"),
                rs.getString("camera_code"),
                rs.getString("camera_name"),
                (Integer) rs.getObject("track_id"),
                rs.getString("body_crop_path"),
                rs.getString("liveness"),
                rs.getBigDecimal("margin"),
                rs.getBigDecimal("reid_score"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
