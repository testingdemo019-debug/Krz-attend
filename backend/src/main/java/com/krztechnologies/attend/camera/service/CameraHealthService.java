package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.domain.CameraHeartbeat;
import com.krztechnologies.attend.camera.domain.CameraStatus;
import com.krztechnologies.attend.camera.repository.CameraHealthQueries;
import com.krztechnologies.attend.camera.repository.CameraHealthQueries.CameraState;
import com.krztechnologies.attend.camera.repository.CameraHealthQueries.OpenOutage;
import com.krztechnologies.attend.camera.repository.CameraRepository;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.realtime.SseHub;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.common.worker.WorkerProperties;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import com.krztechnologies.attend.notification.service.RuleSettings;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Camera health from the worker's heartbeats (Redis contract §4), read every 5 s by {@code CameraHealthJob}: a
 * heartbeat's status becomes the camera status (a missing key means OFFLINE), each transition is persisted (status,
 * outage rows, reconnects), audited when a camera goes offline or comes back, and pushed as SSE {@code camera.status}.
 * NR-01 fires once per outage that lasts longer than its threshold, NR-10 once per silence of the worker heartbeat.
 * Every minute the daily rollup ({@code camera_health_daily}) is recomputed from the outages.
 */
@Service
public class CameraHealthService {

    static final String CAMERA_HEARTBEAT = "krz:hb:camera:";
    static final String WORKER_HEARTBEAT = "krz:hb:worker:";

    private static final Logger LOG = LoggerFactory.getLogger(CameraHealthService.class);
    private static final int HEALTH_DAYS = 7;
    private static final int RECENT_OUTAGES = 10;
    private static final int NR01_DEFAULT_MINUTES = 5;
    private static final int NR10_DEFAULT_MINUTES = 2;
    private static final String NO_HEARTBEAT = "No heartbeat";

    private final CameraHealthQueries queries;
    private final CameraRepository cameras;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RuleSettings rules;
    private final SseHub sse;
    private final TimeService time;
    private final SettingsService settings;
    private final WorkerProperties worker;
    private final TransactionTemplate transactions;
    private final Set<Long> notifiedOutages = ConcurrentHashMap.newKeySet();
    private Instant workerSilentSince;
    private boolean workerNotified;

    public CameraHealthService(
            CameraHealthQueries queries,
            CameraRepository cameras,
            StringRedisTemplate redis,
            JsonMapper json,
            AuditService audit,
            NotificationService notifications,
            RuleSettings rules,
            SseHub sse,
            TimeService time,
            SettingsService settings,
            WorkerProperties worker,
            TransactionTemplate transactions) {
        this.queries = queries;
        this.cameras = cameras;
        this.redis = redis;
        this.json = json;
        this.audit = audit;
        this.notifications = notifications;
        this.rules = rules;
        this.sse = sse;
        this.time = time;
        this.settings = settings;
        this.worker = worker;
        this.transactions = transactions;
    }

    /** SSE {@code camera.status}: non-sensitive, sent to every signed-in user (the topbar pill shows cameras). */
    public record CameraStatusEvent(String code, String name, CameraStatus status, Instant since) {}

    /**
     * The recognition worker's heartbeat (Redis contract §4) for Settings → System.
     *
     * @param gpu null when the worker reports no GPU
     */
    public record WorkerBeat(String version, String provider, Gpu gpu) {}

    /** The worker's GPU: memory in MB, utilisation in percent. */
    public record Gpu(String name, Integer memUsedMb, Integer memTotalMb, Integer utilPct) {}

    /** Empty when the heartbeat has expired (the worker is down) or cannot be read. */
    public Optional<WorkerBeat> workerHeartbeat() {
        try {
            String raw = redis.opsForValue().get(WORKER_HEARTBEAT + worker.id());
            if (raw == null) {
                return Optional.empty();
            }
            JsonNode beat = json.readTree(raw);
            JsonNode gpu = beat.path("gpu");
            return Optional.of(new WorkerBeat(
                    text(beat, "version"),
                    text(beat, "provider"),
                    gpu.isObject()
                            ? new Gpu(
                                    text(gpu, "name"),
                                    integer(gpu, "mem_used_mb"),
                                    integer(gpu, "mem_total_mb"),
                                    integer(gpu, "util_pct"))
                            : null));
        } catch (RuntimeException e) {
            LOG.warn("Worker heartbeat not readable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** One health round: heartbeats → status transitions, then NR-01 and NR-10. Skipped while Redis is unreadable. */
    public synchronized void check() {
        Instant now = time.now();
        List<CameraState> states = queries.states();
        Map<String, CameraHeartbeat> beats;
        boolean workerAlive;
        try {
            beats = heartbeats(states);
            workerAlive = Boolean.TRUE.equals(redis.hasKey(WORKER_HEARTBEAT + worker.id()));
        } catch (RuntimeException e) {
            LOG.warn("Camera health check skipped: Redis is not readable ({})", e.getMessage());
            return;
        }
        for (CameraState camera : states) {
            try {
                transactions.executeWithoutResult(status -> apply(camera, beats.get(camera.code()), now));
            } catch (RuntimeException e) {
                LOG.error("Health check of camera {} failed", camera.code(), e);
            }
        }
        notifyLongOutages(now);
        checkWorker(workerAlive, now);
    }

    /** Recomputes yesterday's (final after midnight) and today's uptime rows. */
    public void rollUp() {
        Instant now = time.now();
        LocalDate today = time.dayOf(now);
        transactions.executeWithoutResult(status -> {
            for (LocalDate day : List.of(today.minusDays(1), today)) {
                queries.rollUp(day, time.startOfDay(day), time.startOfDay(day.plusDays(1)), now);
            }
        });
    }

    @Transactional(readOnly = true)
    public CameraViews.HealthView health(String code) {
        Camera camera = cameras.findByCode(code).orElseThrow(() -> new Errors.NotFound("Camera not found."));
        List<CameraViews.HealthDay> days =
                queries.days(camera.getId(), time.today().minusDays(HEALTH_DAYS - 1L)).stream()
                        .map(d -> new CameraViews.HealthDay(d.day(), d.uptimePct(), d.reconnects(), d.offlineMinutes()))
                        .toList();
        BigDecimal uptime = days.isEmpty()
                ? null
                : days.stream()
                        .map(CameraViews.HealthDay::uptimePct)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(days.size()), 1, RoundingMode.HALF_UP);
        int reconnects =
                days.stream().mapToInt(CameraViews.HealthDay::reconnects).sum();
        List<CameraViews.OutageView> outages = queries.recentOutages(camera.getId(), RECENT_OUTAGES).stream()
                .map(o -> new CameraViews.OutageView(o.startedAt(), o.endedAt(), o.reason()))
                .toList();
        return new CameraViews.HealthView(
                camera.getCode(),
                camera.getStatus(),
                camera.getStatusSince(),
                camera.getLastSeenAt(),
                camera.getFps(),
                camera.getLatencyMs(),
                uptime,
                reconnects,
                days,
                outages);
    }

    /** Average uptime of the last 7 days per camera id (for the camera list). */
    Map<Long, BigDecimal> uptimeLastWeek() {
        return queries.uptimeSince(time.today().minusDays(HEALTH_DAYS - 1L));
    }

    /**
     * Processing was turned on (CONNECTING until the first heartbeat) or off (DISABLED; an open outage ends). Runs in
     * the caller's transaction.
     */
    void processingChanged(Camera camera, Instant now) {
        CameraState state = new CameraState(
                camera.getId(), camera.getCode(), camera.getName(), camera.isEnabled(), camera.getStatus());
        transition(state, camera.isEnabled() ? CameraStatus.CONNECTING : CameraStatus.DISABLED, now, null);
    }

    private void apply(CameraState camera, CameraHeartbeat beat, Instant now) {
        if (!camera.enabled()) {
            transition(camera, CameraStatus.DISABLED, now, null);
            return;
        }
        if (beat != null) {
            queries.recordHeartbeat(camera.id(), beat, now);
        }
        CameraStatus target = beat == null ? CameraStatus.OFFLINE : beat.status();
        transition(camera, target, now, beat == null ? NO_HEARTBEAT : beat.errorCode());
    }

    private void transition(CameraState camera, CameraStatus to, Instant now, String reason) {
        CameraStatus from = camera.status();
        if (from == to || !queries.changeStatus(camera.id(), from, to, now)) {
            return;
        }
        if (to == CameraStatus.OFFLINE) {
            queries.openOutage(camera.id(), now, reason == null ? NO_HEARTBEAT : reason);
            audit.record(
                    AuditActor.SYSTEM,
                    AuditCategory.CAMERAS,
                    "Camera went offline",
                    camera.name(),
                    from.label(),
                    to.label(),
                    null);
        }
        if (from == CameraStatus.OFFLINE) {
            queries.closeOutage(camera.id(), now);
            if (to == CameraStatus.LIVE) {
                audit.record(
                        AuditActor.SYSTEM,
                        AuditCategory.CAMERAS,
                        "Camera back online",
                        camera.name(),
                        from.label(),
                        to.label(),
                        null);
            }
        }
        if (from == CameraStatus.LIVE && (to == CameraStatus.CONNECTING || to == CameraStatus.OFFLINE)) {
            queries.countReconnect(camera.id(), time.dayOf(now));
        }
        var event = new CameraStatusEvent(camera.code(), camera.name(), to, now);
        AfterCommit.run(() -> sse.publishToAll("camera.status", event));
        LOG.info("Camera {} is now {} (was {})", camera.code(), to, from);
    }

    private Map<String, CameraHeartbeat> heartbeats(List<CameraState> states) {
        List<CameraState> enabled = states.stream().filter(CameraState::enabled).toList();
        Map<String, CameraHeartbeat> beats = new HashMap<>();
        if (enabled.isEmpty()) {
            return beats;
        }
        List<String> values = redis.opsForValue()
                .multiGet(enabled.stream().map(c -> CAMERA_HEARTBEAT + c.code()).toList());
        for (int i = 0; i < enabled.size(); i++) {
            String raw = values == null ? null : values.get(i);
            if (raw != null) {
                CameraHeartbeat beat = parse(enabled.get(i).code(), raw);
                if (beat != null) {
                    beats.put(enabled.get(i).code(), beat);
                }
            }
        }
        return beats;
    }

    /** {status: live|connecting|offline, fps, latency_ms, resolution, codec, last_frame_ts, error_code?, …}. */
    private CameraHeartbeat parse(String code, String raw) {
        try {
            JsonNode beat = json.readTree(raw);
            CameraStatus status = switch (beat.path("status").asString("")) {
                case "live" -> CameraStatus.LIVE;
                case "connecting" -> CameraStatus.CONNECTING;
                default -> CameraStatus.OFFLINE;
            };
            return new CameraHeartbeat(
                    status,
                    beat.path("fps").isNumber() ? beat.path("fps").asDouble() : null,
                    beat.path("latency_ms").isNumber() ? beat.path("latency_ms").asInt() : null,
                    text(beat, "resolution"),
                    text(beat, "codec"),
                    lastFrame(text(beat, "last_frame_ts")),
                    beat.path("clock_offset_s").isNumber()
                            ? beat.path("clock_offset_s").asDouble()
                            : null,
                    text(beat, "error_code"));
        } catch (JacksonException e) {
            LOG.warn("Ignoring an unreadable heartbeat of camera {}", code);
            return null;
        }
    }

    private void notifyLongOutages(Instant now) {
        List<OpenOutage> open = queries.openOutagesStartedBefore(now);
        Set<Long> openIds = new HashSet<>();
        open.forEach(o -> openIds.add(o.id()));
        notifiedOutages.retainAll(openIds);
        Instant threshold = now.minus(Duration.ofMinutes(rules.threshold("NR-01", NR01_DEFAULT_MINUTES)));
        for (OpenOutage outage : open) {
            if (outage.startedAt().isAfter(threshold) || !notifiedOutages.add(outage.id())) {
                continue;
            }
            var request = new NotificationRequest(
                    outage.cameraName() + " camera offline",
                    "No video since " + clockTime(outage.startedAt()) + ".",
                    "/settings/cameras",
                    "NR-01:" + outage.cameraCode() + ":" + outage.id(),
                    Map.of("camera", outage.cameraCode()));
            try {
                transactions.executeWithoutResult(status -> notifications.publish("NR-01", request));
            } catch (RuntimeException e) {
                notifiedOutages.remove(outage.id());
                LOG.error("NR-01 for camera {} could not be published", outage.cameraCode(), e);
            }
        }
    }

    private void checkWorker(boolean alive, Instant now) {
        if (alive) {
            workerSilentSince = null;
            workerNotified = false;
            return;
        }
        if (workerSilentSince == null) {
            workerSilentSince = now;
        }
        Duration threshold = Duration.ofMinutes(rules.threshold("NR-10", NR10_DEFAULT_MINUTES));
        if (workerNotified || now.isBefore(workerSilentSince.plus(threshold))) {
            return;
        }
        var request = new NotificationRequest(
                "Recognition worker not responding",
                "No heartbeat from the recognition worker since " + clockTime(workerSilentSince) + ".",
                "/settings/system",
                "NR-10:" + workerSilentSince.getEpochSecond(),
                Map.of("worker", worker.id()));
        try {
            transactions.executeWithoutResult(status -> notifications.publish("NR-10", request));
            workerNotified = true;
        } catch (RuntimeException e) {
            LOG.error("NR-10 could not be published", e);
        }
    }

    /** "9:41 AM" or "09:41", as Settings → General's time format says, in the business time zone. */
    private String clockTime(Instant instant) {
        return TimeFormat.of(settings.general().timeFormat())
                .time(time.zoned(instant).toLocalTime());
    }

    private static Instant lastFrame(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asInt() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
