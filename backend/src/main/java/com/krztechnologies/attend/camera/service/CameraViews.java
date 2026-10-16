package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.CameraStatus;
import com.krztechnologies.attend.camera.domain.Wire;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.rules.service.CameraMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models returned by the camera services (records; mapped to the API as they are). */
public final class CameraViews {

    private CameraViews() {}

    /** The entry point a camera watches, with its location. */
    public record EntryPointRef(String code, String name, String locationCode, String locationName) {}

    /**
     * A camera for Settings → Cameras. The password is never returned, only whether one is set.
     *
     * @param mainStreamUrl the main-stream RTSP URL with the password masked ({@code rtsp://user:••••@…})
     * @param zone the detection zone polygon, empty when the whole image is used
     * @param wire the tripwire, null when none is set (MONITOR cameras)
     * @param fps frames per second of the latest heartbeat
     * @param uptime7d average uptime of the last 7 days in %, null before the first daily rollup
     */
    public record CameraView(
            String code,
            String name,
            EntryPointRef entryPoint,
            CameraMode directionMode,
            @Nullable String mountNote,
            FacesVisibleWhen facesVisibleWhen,
            @Nullable String model,
            String host,
            int rtspPort,
            int onvifPort,
            String mainPath,
            String subPath,
            @Nullable String username,
            boolean passwordSet,
            @Nullable Instant passwordUpdatedAt,
            String mainStreamUrl,
            String subStreamUrl,
            boolean enabled,
            boolean livenessEnabled,
            List<List<Double>> zone,
            @Nullable Wire wire,
            int confirmFrames,
            int ignoreUturnSec,
            CameraStatus status,
            @Nullable Instant statusSince,
            @Nullable Instant lastSeenAt,
            @Nullable BigDecimal fps,
            @Nullable Integer latencyMs,
            @Nullable String resolution,
            @Nullable String codec,
            @Nullable BigDecimal clockOffsetSec,
            @Nullable BigDecimal uptime7d,
            long configVersion,
            int version) {}

    /**
     * A Live View tile: status and the camera's geometry for the tripwire and zone overlays.
     *
     * @param entryPoint the entry point's name
     */
    public record LiveTile(
            String code,
            String name,
            @Nullable String entryPoint,
            CameraStatus status,
            @Nullable Instant statusSince,
            @Nullable BigDecimal fps,
            @Nullable Integer latencyMs,
            @Nullable String resolution,
            CameraMode directionMode,
            List<List<Double>> zone,
            @Nullable Wire wire) {

        /** No video (the status pill's "Camera offline"). */
        public boolean offline() {
            return status == CameraStatus.OFFLINE;
        }

        /** Connecting or reconnecting ("Camera reconnecting"). */
        public boolean connecting() {
            return status == CameraStatus.CONNECTING;
        }
    }

    /**
     * "Test connection".
     *
     * @param errorCode AUTH_FAILED, TIMEOUT, UNREACHABLE or UNSUPPORTED_CODEC when the test failed
     * @param message what went wrong, for the user; null when connected
     * @param clockOffsetSec the camera clock's offset from the server (latest heartbeat), null when unknown
     */
    public record TestResult(
            boolean ok,
            @Nullable String codec,
            @Nullable String resolution,
            @Nullable Double fps,
            @Nullable Integer latencyMs,
            @Nullable String errorCode,
            @Nullable String message,
            @Nullable BigDecimal clockOffsetSec) {}

    /**
     * A camera answering the ONVIF probe.
     *
     * @param inUseBy the name of the configured camera with this IP address, null when free
     */
    public record DiscoveredCamera(
            String ip,
            @Nullable String manufacturer,
            @Nullable String model,
            @Nullable String onvifUrl,
            @Nullable String inUseBy) {}

    /**
     * A calibration test walk.
     *
     * @param status RUNNING, COMPLETED or FAILED
     * @param verdict when completed: PASSED (IN when facing the camera, then OUT), REVERSED (the first crossing was
     *     OUT: flip the inside direction), NO_CROSSINGS or INCONCLUSIVE; null while running
     */
    public record CalibrationView(
            long runId,
            String status,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable String verdict,
            List<WorkerClient.CalibrationCrossing> crossings) {}

    /** One day of camera_health_daily. */
    public record HealthDay(LocalDate day, @Nullable BigDecimal uptimePct, int reconnects, int offlineMinutes) {}

    /** An offline period (open while {@code endedAt} is null). */
    public record OutageView(
            Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable String reason) {}

    /**
     * Health (7 days) in the camera editor.
     *
     * @param uptimePct average uptime of the last 7 days in %, null before the first rollup
     * @param reconnects reconnects in the last 7 days
     */
    public record HealthView(
            String code,
            CameraStatus status,
            @Nullable Instant statusSince,
            @Nullable Instant lastSeenAt,
            @Nullable BigDecimal fps,
            @Nullable Integer latencyMs,
            @Nullable BigDecimal uptimePct,
            int reconnects,
            List<HealthDay> days,
            List<OutageView> outages) {}

    /** The per-camera liveness flag shown in Settings → Recognition → Liveness. */
    public record CameraLiveness(String cameraCode, String cameraName, boolean enabled) {}
}
