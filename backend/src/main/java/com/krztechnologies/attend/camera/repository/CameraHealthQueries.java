package com.krztechnologies.attend.camera.repository;

import com.krztechnologies.attend.camera.domain.CameraHeartbeat;
import com.krztechnologies.attend.camera.domain.CameraStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Camera status, heartbeat metrics, outages and the daily health rollup, written with SQL so that a heartbeat never
 * bumps the camera's optimistic-lock version. Status changes are compare-and-set, so a heartbeat can never overwrite
 * a status an administrator has just changed.
 */
@Repository
public class CameraHealthQueries {

    private final JdbcClient jdbc;

    public CameraHealthQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What the health check needs of every camera. */
    public record CameraState(long id, String code, String name, boolean enabled, CameraStatus status) {}

    /** An outage that is still open. */
    public record OpenOutage(long id, String cameraCode, String cameraName, Instant startedAt) {}

    /** One row of camera_health_daily. */
    public record DayHealth(LocalDate day, BigDecimal uptimePct, int reconnects, int offlineMinutes) {}

    /** One row of camera_outage. */
    public record Outage(Instant startedAt, Instant endedAt, String reason) {}

    public List<CameraState> states() {
        return jdbc.sql("SELECT id, code, name, enabled, status FROM attend.camera ORDER BY code")
                .query((rs, n) -> new CameraState(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBoolean("enabled"),
                        CameraStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /** The metrics of the latest heartbeat (fps, latency, resolution, codec, clock offset, last frame). */
    public void recordHeartbeat(long cameraId, CameraHeartbeat beat, Instant receivedAt) {
        jdbc.sql("""
                        UPDATE attend.camera
                           SET fps = :fps, latency_ms = :latency, resolution = :resolution, codec = :codec,
                               clock_offset_s = :offset, last_seen_at = :seen
                         WHERE id = :id
                        """)
                .param("fps", beat.fps() == null ? null : BigDecimal.valueOf(Math.min(beat.fps(), 999.99)))
                .param("latency", beat.latencyMs() == null ? null : Math.max(0, beat.latencyMs()))
                .param("resolution", truncate(beat.resolution(), 20))
                .param("codec", truncate(beat.codec(), 40))
                .param(
                        "offset",
                        beat.clockOffsetSeconds() == null ? null : BigDecimal.valueOf(beat.clockOffsetSeconds()))
                .param("seen", Timestamp.from(beat.lastFrame() == null ? receivedAt : beat.lastFrame()))
                .param("id", cameraId)
                .update();
    }

    /** Compare-and-set; false when the status was no longer {@code from}. */
    public boolean changeStatus(long cameraId, CameraStatus from, CameraStatus to, Instant at) {
        return jdbc.sql("""
                        UPDATE attend.camera SET status = :to, status_since = :at
                         WHERE id = :id AND status = :from
                        """)
                        .param("to", to.name())
                        .param("at", Timestamp.from(at))
                        .param("id", cameraId)
                        .param("from", from.name())
                        .update()
                == 1;
    }

    /** Opens the camera's outage; a no-op when one is already open (one open outage per camera). */
    public void openOutage(long cameraId, Instant at, String reason) {
        jdbc.sql("""
                        INSERT INTO attend.camera_outage (camera_id, started_at, reason)
                        VALUES (:camera, :at, :reason)
                        ON CONFLICT (camera_id) WHERE ended_at IS NULL DO NOTHING
                        """)
                .param("camera", cameraId)
                .param("at", Timestamp.from(at))
                .param("reason", truncate(reason, 200))
                .update();
    }

    public void closeOutage(long cameraId, Instant at) {
        jdbc.sql("""
                        UPDATE attend.camera_outage SET ended_at = greatest(started_at, :at)
                         WHERE camera_id = :camera AND ended_at IS NULL
                        """).param("at", Timestamp.from(at)).param("camera", cameraId).update();
    }

    /** A LIVE camera lost its stream: one more reconnect on that day. */
    public void countReconnect(long cameraId, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO attend.camera_health_daily (camera_id, day, uptime_pct, reconnects, offline_minutes)
                        VALUES (:camera, CAST(:day AS date), 100, 1, 0)
                        ON CONFLICT (camera_id, day) DO UPDATE SET reconnects = attend.camera_health_daily.reconnects + 1
                        """).param("camera", cameraId).param("day", day).update();
    }

    public List<OpenOutage> openOutagesStartedBefore(Instant before) {
        return jdbc.sql("""
                        SELECT o.id, c.code, c.name, o.started_at
                          FROM attend.camera_outage o
                          JOIN attend.camera c ON c.id = o.camera_id
                         WHERE o.ended_at IS NULL AND o.started_at <= :before
                         ORDER BY o.started_at
                        """)
                .param("before", Timestamp.from(before))
                .query((rs, n) -> new OpenOutage(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"), instant(rs, "started_at")))
                .list();
    }

    /**
     * Recomputes uptime and offline minutes of every camera for one day from the outages: the window runs from the
     * start of the day (or the camera's creation) to the end of the day (or now). Reconnects are counted as they
     * happen and kept.
     */
    public void rollUp(LocalDate day, Instant dayStart, Instant dayEnd, Instant now) {
        jdbc.sql("""
                        WITH bounds AS (
                          SELECT CAST(:start AS timestamptz) AS day_start,
                                 least(CAST(:end AS timestamptz), CAST(:now AS timestamptz)) AS day_end
                        ), windows AS (
                          SELECT c.id, greatest(b.day_start, c.created_at) AS w_start, b.day_end AS w_end
                            FROM attend.camera c CROSS JOIN bounds b
                           WHERE c.created_at < b.day_end
                        ), measured AS (
                          SELECT w.id,
                                 extract(epoch FROM (w.w_end - w.w_start)) AS elapsed_s,
                                 coalesce((SELECT sum(extract(epoch FROM (least(coalesce(o.ended_at, w.w_end), w.w_end)
                                                                           - greatest(o.started_at, w.w_start))))
                                             FROM attend.camera_outage o
                                            WHERE o.camera_id = w.id
                                              AND o.started_at < w.w_end
                                              AND coalesce(o.ended_at, w.w_end) > w.w_start), 0) AS offline_s
                            FROM windows w
                        )
                        INSERT INTO attend.camera_health_daily (camera_id, day, uptime_pct, reconnects, offline_minutes)
                        SELECT id, CAST(:day AS date),
                               CASE WHEN elapsed_s <= 0 THEN 100
                                    ELSE round(greatest(0, least(100, 100 * (1 - offline_s / elapsed_s))), 2) END,
                               0,
                               least(1440, round(offline_s / 60))::int
                          FROM measured
                        ON CONFLICT (camera_id, day) DO UPDATE
                           SET uptime_pct = EXCLUDED.uptime_pct, offline_minutes = EXCLUDED.offline_minutes
                        """)
                .param("start", Timestamp.from(dayStart))
                .param("end", Timestamp.from(dayEnd))
                .param("now", Timestamp.from(now))
                .param("day", day)
                .update();
    }

    /** The daily rows from {@code from} on, newest first. */
    public List<DayHealth> days(long cameraId, LocalDate from) {
        return jdbc.sql("""
                        SELECT day, uptime_pct, reconnects, offline_minutes FROM attend.camera_health_daily
                         WHERE camera_id = :camera AND day >= CAST(:from AS date)
                         ORDER BY day DESC
                        """)
                .param("camera", cameraId)
                .param("from", from)
                .query((rs, n) -> new DayHealth(
                        rs.getObject("day", LocalDate.class),
                        rs.getBigDecimal("uptime_pct"),
                        rs.getInt("reconnects"),
                        rs.getInt("offline_minutes")))
                .list();
    }

    public List<Outage> recentOutages(long cameraId, int limit) {
        return jdbc.sql("""
                        SELECT started_at, ended_at, reason FROM attend.camera_outage
                         WHERE camera_id = :camera ORDER BY started_at DESC LIMIT :limit
                        """)
                .param("camera", cameraId)
                .param("limit", limit)
                .query((rs, n) ->
                        new Outage(instant(rs, "started_at"), instant(rs, "ended_at"), rs.getString("reason")))
                .list();
    }

    /** Average uptime per camera over the days from {@code from} on (cameras without rows are missing). */
    public Map<Long, BigDecimal> uptimeSince(LocalDate from) {
        Map<Long, BigDecimal> uptime = new HashMap<>();
        jdbc.sql("""
                        SELECT camera_id, round(avg(uptime_pct), 1) AS uptime FROM attend.camera_health_daily
                         WHERE day >= CAST(:from AS date) GROUP BY camera_id
                        """).param("from", from).query(rs -> {
            uptime.put(rs.getLong("camera_id"), rs.getBigDecimal("uptime"));
        });
        return uptime;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
