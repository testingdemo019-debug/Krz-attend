package com.krztechnologies.attend.crossing.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The writes of crossing ingestion: the crossing row (idempotent by {@code crossing_uid}) and the review item it creates
 * or joins (an unknown face joins the open item of its cluster). Cameras are read by code (read-only, D-038).
 */
@Repository
public class IngestStore {

    private final JdbcClient jdbc;

    public IngestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The camera a message names. */
    public record CameraRef(long id, String code, String name) {}

    /** A crossing as ingestion stores it. */
    public record NewCrossing(
            UUID crossingUid,
            long cameraId,
            Instant occurredAt,
            Instant receivedAt,
            String direction,
            boolean uTurn,
            String method,
            UUID employeeId,
            LocalDate shiftDay,
            String decision,
            BigDecimal score,
            BigDecimal secondScore,
            BigDecimal margin,
            Integer agreeK,
            Integer agreeN,
            BigDecimal reidScore,
            String liveness,
            BigDecimal livenessScore,
            String quality,
            String candidates,
            String faceCrop,
            String faceCropBlur,
            String bodyCrop,
            String bodyCropBlur,
            String unknownLabel,
            String unknownCluster,
            String modelVersion,
            Integer trackId,
            String workerId,
            String traceId,
            boolean enrolmentCapture,
            boolean directionUnsure) {}

    /** A stored crossing: its id and EV code. */
    public record Stored(long id, String code) {}

    /** A review item: its id and RV code. */
    public record ReviewRef(long id, String code) {}

    public Optional<CameraRef> camera(String code) {
        return jdbc.sql("SELECT id, code, name FROM attend.camera WHERE code = :code")
                .param("code", code)
                .query((rs, n) -> new CameraRef(rs.getLong("id"), rs.getString("code"), rs.getString("name")))
                .optional();
    }

    /** Inserts the crossing; empty when a crossing with this {@code crossing_uid} exists already (a redelivery). */
    public Optional<Stored> insert(NewCrossing c) {
        return jdbc.sql("""
                        INSERT INTO attend.crossing (
                            code, crossing_uid, camera_id, occurred_at, received_at, direction, u_turn, method, employee_id,
                            shift_day, decision, score, second_score, margin, agree_k, agree_n, reid_score, liveness,
                            liveness_score, quality, candidates, face_crop_path, face_crop_blur_path, body_crop_path,
                            body_crop_blur_path, unknown_label, unknown_cluster, model_version, track_id, worker_id, trace_id,
                            enrolment_capture, direction_unsure)
                        VALUES (
                            attend.next_code('EV-', 'attend.seq_crossing_code', 6), :uid, :camera, :occurredAt, :receivedAt,
                            :direction, :uTurn, :method, :employee, :shiftDay, :decision, :score, :secondScore, :margin,
                            :agreeK, :agreeN, :reidScore, :liveness, :livenessScore, CAST(:quality AS jsonb),
                            CAST(:candidates AS jsonb), :faceCrop, :faceCropBlur, :bodyCrop, :bodyCropBlur, :unknownLabel,
                            :unknownCluster, :modelVersion, :trackId, :workerId, :traceId, :enrolmentCapture,
                            :directionUnsure)
                        ON CONFLICT (crossing_uid) DO NOTHING
                        RETURNING id, code
                        """)
                .param("uid", c.crossingUid())
                .param("camera", c.cameraId())
                .param("occurredAt", Timestamp.from(c.occurredAt()))
                .param("receivedAt", Timestamp.from(c.receivedAt()))
                .param("direction", c.direction())
                .param("uTurn", c.uTurn())
                .param("method", c.method())
                .param("employee", c.employeeId())
                .param("shiftDay", c.shiftDay())
                .param("decision", c.decision())
                .param("score", c.score())
                .param("secondScore", c.secondScore())
                .param("margin", c.margin())
                .param("agreeK", c.agreeK())
                .param("agreeN", c.agreeN())
                .param("reidScore", c.reidScore())
                .param("liveness", c.liveness())
                .param("livenessScore", c.livenessScore())
                .param("quality", c.quality())
                .param("candidates", c.candidates())
                .param("faceCrop", c.faceCrop())
                .param("faceCropBlur", c.faceCropBlur())
                .param("bodyCrop", c.bodyCrop())
                .param("bodyCropBlur", c.bodyCropBlur())
                .param("unknownLabel", c.unknownLabel())
                .param("unknownCluster", c.unknownCluster())
                .param("modelVersion", c.modelVersion())
                .param("trackId", c.trackId())
                .param("workerId", c.workerId())
                .param("traceId", c.traceId())
                .param("enrolmentCapture", c.enrolmentCapture())
                .param("directionUnsure", c.directionUnsure())
                .query((rs, n) -> new Stored(rs.getLong("id"), rs.getString("code")))
                .optional();
    }

    /** The open item of an unknown-face cluster (locked, so a second sighting in parallel waits). */
    public Optional<ReviewRef> openUnknownItem(String cluster) {
        return jdbc.sql("""
                        SELECT id, code FROM attend.review_item
                         WHERE type = 'UNKNOWN' AND status = 'OPEN' AND unknown_cluster = :cluster
                         ORDER BY id LIMIT 1
                         FOR UPDATE
                        """)
                .param("cluster", cluster)
                .query((rs, n) -> new ReviewRef(rs.getLong("id"), rs.getString("code")))
                .optional();
    }

    public ReviewRef createReviewItem(String type, String label, String cluster, Instant sighting) {
        return jdbc.sql("""
                        INSERT INTO attend.review_item (code, type, label, unknown_cluster, created_at, last_sighting_at)
                        VALUES (attend.next_code('RV-', 'attend.seq_review_code', 4), :type, :label, :cluster, :at, :at)
                        RETURNING id, code
                        """)
                .param("type", type)
                .param("label", label)
                .param("cluster", cluster)
                .param("at", Timestamp.from(sighting))
                .query((rs, n) -> new ReviewRef(rs.getLong("id"), rs.getString("code")))
                .single();
    }

    /** Another sighting of an open item. */
    public void sighted(long itemId, Instant sighting) {
        jdbc.sql("""
                        UPDATE attend.review_item SET last_sighting_at = greatest(last_sighting_at, :at), version = version + 1
                         WHERE id = :id
                        """).param("at", Timestamp.from(sighting)).param("id", itemId).update();
    }

    /** Links a crossing to its review item and stores its effect (a crossing without identity). */
    public void linkReview(long crossingId, Long itemId, String effect, String reason) {
        jdbc.sql("""
                        UPDATE attend.crossing SET review_item_id = :item, effect = :effect, effect_reason = :reason
                         WHERE id = :id
                        """)
                .param("item", itemId)
                .param("effect", effect)
                .param("reason", reason)
                .param("id", crossingId)
                .update();
    }
}
