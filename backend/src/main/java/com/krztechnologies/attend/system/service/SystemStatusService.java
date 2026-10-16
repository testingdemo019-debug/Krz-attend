package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.attendance.service.DayCloseService;
import com.krztechnologies.attend.camera.service.CameraHealthService;
import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.camera.service.LiveViewService;
import com.krztechnologies.attend.camera.service.MediaRelayReconciler;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.crossing.service.CrossingStreamConsumer;
import com.krztechnologies.attend.directory.service.DirectorySyncService;
import com.krztechnologies.attend.hrms.service.HrmsOutboxService;
import com.krztechnologies.attend.notification.service.MailStatus;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings → System status rows and the topbar status pill. Each check is bounded and degrades to a row saying what
 * is wrong; nothing here throws.
 */
@Service
public class SystemStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(SystemStatusService.class);
    private static final double DISK_WARN_FREE_RATIO = 0.15;
    private static final Duration BACKUP_WARN_AGE = Duration.ofHours(36);

    private final JdbcClient jdbc;
    private final RedisConnectionFactory redis;
    private final MailStatus mail;
    private final FeatureFlags features;
    private final DirectorySyncService directorySync;
    private final CrossingStreamConsumer crossings;
    private final HrmsOutboxService hrms;
    private final LiveViewService cameras;
    private final CameraHealthService health;
    private final MediaRelayReconciler mediaRelay;
    private final DayCloseService dayClose;
    private final SettingsService settings;
    private final AppProperties app;
    private final TimeService time;
    private final JsonMapper json;

    public SystemStatusService(
            JdbcClient jdbc,
            RedisConnectionFactory redis,
            MailStatus mail,
            FeatureFlags features,
            DirectorySyncService directorySync,
            CrossingStreamConsumer crossings,
            HrmsOutboxService hrms,
            LiveViewService cameras,
            CameraHealthService health,
            MediaRelayReconciler mediaRelay,
            DayCloseService dayClose,
            SettingsService settings,
            AppProperties app,
            TimeService time,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mail = mail;
        this.features = features;
        this.directorySync = directorySync;
        this.crossings = crossings;
        this.hrms = hrms;
        this.cameras = cameras;
        this.health = health;
        this.mediaRelay = mediaRelay;
        this.dayClose = dayClose;
        this.settings = settings;
        this.app = app;
        this.time = time;
        this.json = json;
    }

    /** Status level: {@code ok}, {@code warn} or {@code bad} (the prototype's pill colours). */
    public enum Level {
        OK,
        WARN,
        BAD,
        DISABLED
    }

    /**
     * One service in the status list.
     *
     * @param key a stable key; camera rows are {@code camera:<code>}
     * @param detail the muted sub-label after the label (cameras: "CAM-01 · IN + OUT"), else null
     */
    public record StatusRow(
            String key,
            String label,
            Level level,
            String text,
            @Nullable String detail) {

        public StatusRow(String key, String label, Level level, String text) {
            this(key, label, level, text, null);
        }
    }

    /** The topbar pill and the rows every signed-in user may see. */
    public record Summary(Level level, String text, List<StatusRow> rows) {}

    /** The pill: its level and text. */
    record Pill(Level level, String text) {}

    /** Every service, for Settings → System. */
    public List<StatusRow> status() {
        List<StatusRow> rows = new ArrayList<>();
        List<CameraViews.LiveTile> tiles = cameras.tiles();
        tiles.forEach(tile -> rows.add(camera(tile, "Camera ingest · ")));
        var beat = health.workerHeartbeat();
        rows.add(worker(beat));
        rows.add(gpu(beat));
        rows.add(mediaRelay());
        rows.add(database());
        rows.add(redis());
        rows.add(dayCloseJob());
        rows.add(hrms(hrms.counts()));
        rows.add(directorySync());
        rows.add(mail());
        rows.add(new StatusRow("push", "Mobile push", Level.DISABLED, "Disabled — mobile app not available yet"));
        rows.add(crossingIntake());
        rows.add(disk());
        rows.add(lastBackup());
        return rows;
    }

    /**
     * The prototype's {@code systemHealth()} and its status popover: the pill by priority (camera offline →
     * reconnecting → HRMS failed → punches blocked → directory sync failed → all normal) and the non-sensitive rows
     * (cameras, worker, day close, KRZ HRMS, directory sync).
     */
    public Summary summary() {
        List<CameraViews.LiveTile> tiles = cameras.tiles();
        Map<String, Long> outbox = hrms.counts();
        StatusRow sync = directorySync();
        List<StatusRow> rows = new ArrayList<>();
        tiles.forEach(tile -> rows.add(camera(tile, "")));
        rows.add(worker(health.workerHeartbeat()));
        rows.add(dayCloseJob());
        rows.add(hrms(outbox));
        rows.add(sync);
        Pill pill = pill(
                tiles.size(),
                (int) tiles.stream().filter(CameraViews.LiveTile::offline).count(),
                (int) tiles.stream().filter(CameraViews.LiveTile::connecting).count(),
                outbox.get("FAILED"),
                outbox.get("BLOCKED_NO_EMPLOYEE_ID"),
                sync.level() == Level.BAD);
        return new Summary(pill.level(), pill.text(), rows);
    }

    /** The pill's priority order (the prototype's {@code systemHealth}). */
    static Pill pill(int cameras, int offline, int reconnecting, long failed, long blocked, boolean syncFailed) {
        if (offline > 0) {
            return new Pill(
                    Level.BAD,
                    offline == cameras ? "Camera offline" : plural(offline, "camera", "cameras") + " offline");
        }
        if (reconnecting > 0) {
            return new Pill(Level.WARN, "Camera reconnecting");
        }
        if (failed > 0) {
            return new Pill(Level.WARN, plural(failed, "HRMS push", "HRMS pushes") + " failed");
        }
        if (blocked > 0) {
            return new Pill(Level.WARN, plural(blocked, "punch", "punches") + " blocked");
        }
        if (syncFailed) {
            return new Pill(Level.WARN, "Directory sync failed");
        }
        return new Pill(Level.OK, "All systems normal");
    }

    /** "1 camera", "2 cameras". */
    static String plural(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private StatusRow camera(CameraViews.LiveTile tile, String prefix) {
        String key = "camera:" + tile.code();
        String label = prefix + tile.name();
        String detail = tile.code() + " · " + tile.directionMode().shortLabel();
        if (tile.offline()) {
            return new StatusRow(
                    key,
                    label,
                    Level.BAD,
                    tile.statusSince() == null ? "Offline" : "Offline since " + clock(tile.statusSince()),
                    detail);
        }
        if (tile.connecting()) {
            return new StatusRow(key, label, Level.WARN, "Reconnecting…", detail);
        }
        return new StatusRow(key, label, Level.OK, liveText(tile), detail);
    }

    /** "12 fps · 180 ms · 1280×720", leaving out what the camera has not reported. */
    private static String liveText(CameraViews.LiveTile tile) {
        List<String> parts = new ArrayList<>();
        if (tile.fps() != null) {
            parts.add(tile.fps().stripTrailingZeros().toPlainString() + " fps");
        }
        if (tile.latencyMs() != null) {
            parts.add(tile.latencyMs() + " ms");
        }
        if (tile.resolution() != null) {
            parts.add(tile.resolution());
        }
        return parts.isEmpty() ? "Live" : String.join(" · ", parts);
    }

    private StatusRow worker(Optional<CameraHealthService.WorkerBeat> beat) {
        return beat.map(b -> {
                    List<String> parts = new ArrayList<>();
                    parts.add("Running");
                    if (b.version() != null) {
                        parts.add(b.version());
                    }
                    if (b.provider() != null) {
                        parts.add(b.provider());
                    }
                    if (b.gpu() != null && b.gpu().utilPct() != null) {
                        parts.add("GPU " + b.gpu().utilPct() + "%");
                    }
                    return new StatusRow("worker", "Recognition worker", Level.OK, String.join(" · ", parts));
                })
                .orElse(new StatusRow("worker", "Recognition worker", Level.BAD, "No heartbeat"));
    }

    private static StatusRow gpu(Optional<CameraHealthService.WorkerBeat> beat) {
        if (beat.isEmpty()) {
            return new StatusRow("gpu", "GPU", Level.WARN, "Unknown — no worker heartbeat");
        }
        CameraHealthService.Gpu gpu = beat.get().gpu();
        if (gpu == null) {
            return new StatusRow("gpu", "GPU", Level.WARN, "No GPU reported — recognition runs on the CPU");
        }
        List<String> parts = new ArrayList<>();
        parts.add(gpu.name() == null ? "GPU" : gpu.name());
        if (gpu.memUsedMb() != null && gpu.memTotalMb() != null) {
            parts.add(gpu.memUsedMb() + " of " + gpu.memTotalMb() + " MB");
        }
        if (gpu.utilPct() != null) {
            parts.add(gpu.utilPct() + "% busy");
        }
        return new StatusRow("gpu", "GPU", Level.OK, String.join(" · ", parts));
    }

    private StatusRow mediaRelay() {
        return mediaRelay
                .lastCheck()
                .map(check -> check.reachable()
                        ? new StatusRow(
                                "media-relay",
                                "Media relay",
                                Level.OK,
                                plural(check.streams(), "stream", "streams") + " · checked " + clock(check.at()))
                        : new StatusRow(
                                "media-relay",
                                "Media relay",
                                Level.BAD,
                                "Not reachable · checked " + clock(check.at())))
                .orElse(new StatusRow("media-relay", "Media relay", Level.WARN, "Not checked yet"));
    }

    private StatusRow dayCloseJob() {
        try {
            var last = dayClose.runs(0, 1).items();
            return new StatusRow(
                    "day-close",
                    "Day-close job",
                    Level.OK,
                    last.isEmpty()
                            ? "Not run yet"
                            : "Last run " + dateTime(last.getFirst().ranAt()));
        } catch (RuntimeException e) {
            LOG.warn("Day-close status check failed: {}", e.getMessage());
            return new StatusRow("day-close", "Day-close job", Level.WARN, "Runs not readable");
        }
    }

    private StatusRow database() {
        try {
            String version = jdbc.sql("SELECT current_setting('server_version')")
                    .query(String.class)
                    .single();
            return new StatusRow("database", "Database", Level.OK, "PostgreSQL " + version);
        } catch (RuntimeException e) {
            LOG.warn("Database status check failed: {}", e.getMessage());
            return new StatusRow("database", "Database", Level.BAD, "Not reachable");
        }
    }

    private StatusRow redis() {
        try (var connection = redis.getConnection()) {
            String pong = connection.ping();
            return new StatusRow("redis", "Redis", "PONG".equalsIgnoreCase(pong) ? Level.OK : Level.WARN, "Connected");
        } catch (RuntimeException e) {
            LOG.warn("Redis status check failed: {}", e.getMessage());
            return new StatusRow("redis", "Redis", Level.BAD, "Not reachable");
        }
    }

    /**
     * While HRMS is off: punches are held (blocked ones still warn). On: the sender's queue, as the prototype's status
     * popover writes it ("2 failed · 5 pending · 1 blocked").
     */
    private StatusRow hrms(Map<String, Long> counts) {
        long failed = counts.get("FAILED");
        long pending = counts.get("PENDING");
        long blocked = counts.get("BLOCKED_NO_EMPLOYEE_ID");
        String blockedText = blocked > 0 ? " · " + blocked + " blocked" : "";
        if (!features.hrmsEnabled()) {
            return new StatusRow(
                    "hrms",
                    "KRZ HRMS",
                    blocked > 0 ? Level.WARN : Level.DISABLED,
                    "Not connected — punches are held" + blockedText);
        }
        return new StatusRow(
                "hrms",
                "KRZ HRMS",
                failed > 0 || blocked > 0 ? Level.WARN : Level.OK,
                (failed > 0 ? failed + " failed" : "OK")
                        + (pending > 0 ? " · " + pending + " pending" : "")
                        + blockedText);
    }

    private StatusRow mail() {
        if (!features.mailEnabled()) {
            return new StatusRow("mail", "E-mail", Level.DISABLED, "Turned off (MAIL_ENABLED=false)");
        }
        var state = mail.current();
        if (state.failed() > 0) {
            return new StatusRow("mail", "E-mail", Level.WARN, state.failed() + " e-mail(s) could not be sent");
        }
        return new StatusRow(
                "mail",
                "E-mail",
                Level.OK,
                state.lastSentAt() == null
                        ? "Microsoft Graph · nothing sent yet"
                        : "Microsoft Graph · last sent " + dateTime(state.lastSentAt()));
    }

    private StatusRow directorySync() {
        return directorySync
                .latest()
                .map(run -> "FAILED".equals(run.status())
                        ? new StatusRow("directory", "Directory sync", Level.BAD, "Last run " + run.code() + " failed")
                        : new StatusRow(
                                "directory",
                                "Directory sync",
                                Level.OK,
                                "Last run " + run.code() + " · " + run.status()))
                .orElse(new StatusRow("directory", "Directory sync", Level.WARN, "Not run yet"));
    }

    /** Stream messages that could not be stored (Redis contract §1, poison messages). */
    private StatusRow crossingIntake() {
        try {
            long dead = crossings.deadLetterCount();
            return dead == 0
                    ? new StatusRow("crossings", "Crossing intake", Level.OK, "Every crossing was processed")
                    : new StatusRow(
                            "crossings",
                            "Crossing intake",
                            Level.WARN,
                            dead == 1
                                    ? "1 crossing could not be processed"
                                    : dead + " crossings could not be processed");
        } catch (RuntimeException e) {
            LOG.warn("Crossing intake status check failed: {}", e.getMessage());
            return new StatusRow("crossings", "Crossing intake", Level.WARN, "Unknown (Redis not reachable)");
        }
    }

    private StatusRow disk() {
        try {
            Path dir = app.dataPath().toAbsolutePath();
            Files.createDirectories(dir);
            var store = Files.getFileStore(dir);
            double freeRatio = (double) store.getUsableSpace() / store.getTotalSpace();
            long freeGb = store.getUsableSpace() / (1024L * 1024 * 1024);
            return new StatusRow(
                    "disk",
                    "Disk space",
                    freeRatio < DISK_WARN_FREE_RATIO ? Level.WARN : Level.OK,
                    freeGb + " GB free (" + Math.round(freeRatio * 100) + " %)");
        } catch (IOException e) {
            return new StatusRow(
                    "disk", "Disk space", Level.WARN, "Unknown (" + e.getClass().getSimpleName() + ")");
        }
    }

    private StatusRow lastBackup() {
        Path file = app.dataPath().resolve("backups").resolve("last-backup.json");
        if (!Files.isRegularFile(file)) {
            return new StatusRow("backup", "Last backup", Level.WARN, "No backup yet");
        }
        try {
            JsonNode result = json.readTree(Files.readString(file));
            Instant finished = Instant.parse(result.path("finishedAt").asString());
            boolean success = "SUCCESS".equals(result.path("status").asString());
            boolean stale = finished.plus(BACKUP_WARN_AGE).isBefore(time.now());
            Level level = !success ? Level.BAD : stale ? Level.WARN : Level.OK;
            return new StatusRow(
                    "backup", "Last backup", level, (success ? "Succeeded " : "Failed ") + dateTime(finished));
        } catch (IOException | RuntimeException e) {
            return new StatusRow("backup", "Last backup", Level.WARN, "Unreadable last-backup.json");
        }
    }

    /** "9:41 AM" in the business time zone, as Settings → General writes times. */
    String clock(Instant at) {
        return TimeFormat.of(settings.general().timeFormat())
                .time(time.zoned(at).toLocalTime());
    }

    /** "12 Oct 2026, 9:41 AM". */
    private String dateTime(Instant at) {
        return TimeFormat.of(settings.general().timeFormat())
                .dateTime(time.zoned(at).toLocalDateTime());
    }
}
