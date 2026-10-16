package com.krztechnologies.attend.camera.web;

import com.krztechnologies.attend.camera.domain.CameraGeometry;
import com.krztechnologies.attend.camera.domain.Wire;
import com.krztechnologies.attend.camera.service.CameraConnectionService;
import com.krztechnologies.attend.camera.service.CameraHealthService;
import com.krztechnologies.attend.camera.service.CameraInput;
import com.krztechnologies.attend.camera.service.CameraService;
import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Cameras: cameras and their connection, password, zone and tripwire, test connection, snapshot, ONVIF
 * discovery, calibration test walks and health.
 */
@RestController
@RequestMapping("/api/v1/settings/cameras")
@Validated
public class CameraController {

    private static final String CAMERA_CODE = "^CAM-\\d{2,}$";
    private static final String CIDR_24 =
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}" + "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)/(2[4-9]|3[0-2])$";

    private final CameraService cameras;
    private final CameraConnectionService connections;
    private final CameraHealthService health;
    private final CurrentUser currentUser;

    public CameraController(
            CameraService cameras,
            CameraConnectionService connections,
            CameraHealthService health,
            CurrentUser currentUser) {
        this.cameras = cameras;
        this.connections = connections;
        this.health = health;
        this.currentUser = currentUser;
    }

    /** @param password the camera user's password; stored encrypted, never returned */
    public record PasswordRequest(
            @NotNull @Size(min = 8, max = 128, message = "Use at least 8 characters.")
            String password) {}

    /**
     * @param zone the detection zone [[x,y],…] normalised 0..1 (at least 3 points; empty = the whole image)
     * @param wire the tripwire; null only in MONITOR mode
     */
    public record GeometryRequest(
            @Size(max = 20) List<List<Double>> zone,
            @Nullable @Valid Wire wire,
            @NotNull @Min(2) @Max(5) Integer confirmFrames,
            @NotNull @Min(3) @Max(10) Integer ignoreUturnSec,
            @NotNull Integer version) {}

    public record FlipRequest(@NotNull Integer version) {}

    /** @param subnet an IPv4 network of /24 or smaller; empty = CAMERA_DISCOVERY_SUBNET of the worker */
    public record DiscoverRequest(
            @Nullable @Pattern(regexp = CIDR_24, message = "Use an IPv4 network of /24 or smaller, e.g. 192.168.1.0/24")
            String subnet) {}

    /** @param durationS how long the worker reports crossings to the test walk (default 60 s) */
    public record CalibrationRequest(
            @Nullable @Min(10) @Max(300) Integer durationS) {}

    @Operation(summary = "All cameras with status, frame rate and 7-day uptime")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @GetMapping
    public List<CameraViews.CameraView> list() {
        return cameras.list();
    }

    @Operation(summary = "Add a camera (CAM-nn) with the starting zone and tripwire; set its password next")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PostMapping
    public CameraViews.CameraView create(@Valid @RequestBody CameraInput input) {
        return cameras.create(input, actor());
    }

    @Operation(summary = "One camera (the password is never returned, only whether it is set)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @GetMapping("/{code}")
    public CameraViews.CameraView get(@PathVariable @Pattern(regexp = CAMERA_CODE) String code) {
        return cameras.get(code);
    }

    @Operation(summary = "Save a camera's details and connection (send the version you edited)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PutMapping("/{code}")
    public CameraViews.CameraView update(
            @PathVariable @Pattern(regexp = CAMERA_CODE) String code, @Valid @RequestBody CameraInput input) {
        return cameras.update(code, input);
    }

    @Operation(summary = "Set or change the camera password (stored encrypted, never shown again)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PutMapping("/{code}/password")
    public CameraViews.CameraView setPassword(
            @PathVariable @Pattern(regexp = CAMERA_CODE) String code, @Valid @RequestBody PasswordRequest request) {
        return cameras.setPassword(code, request.password(), actor());
    }

    @Operation(summary = "Test the connection with the saved settings (codec, resolution, latency)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PostMapping("/{code}/test")
    public CameraViews.TestResult test(@PathVariable @Pattern(regexp = CAMERA_CODE) String code) {
        return connections.test(code);
    }

    @Operation(summary = "Save the detection zone, tripwire, confirm frames and U-turn window")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PutMapping("/{code}/geometry")
    public CameraViews.CameraView setGeometry(
            @PathVariable @Pattern(regexp = CAMERA_CODE) String code, @Valid @RequestBody GeometryRequest request) {
        return cameras.setGeometry(
                code,
                new CameraGeometry(request.zone(), request.wire(), request.confirmFrames(), request.ignoreUturnSec()),
                request.version());
    }

    @Operation(summary = "Flip the inside direction of the tripwire")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PostMapping("/{code}/flip")
    public CameraViews.CameraView flip(
            @PathVariable @Pattern(regexp = CAMERA_CODE) String code, @Valid @RequestBody FlipRequest request) {
        return cameras.flip(code, request.version());
    }

    @Operation(summary = "The latest frame of the main stream as JPEG (not cached)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @GetMapping("/{code}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable @Pattern(regexp = CAMERA_CODE) String code) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(connections.snapshot(code));
    }

    @Operation(summary = "Start a calibration test walk (no punches are created while it runs)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PostMapping("/{code}/calibration")
    public CameraViews.CalibrationView startCalibration(
            @PathVariable @Pattern(regexp = CAMERA_CODE) String code,
            @Valid @RequestBody(required = false) CalibrationRequest request) {
        int seconds = request == null || request.durationS() == null
                ? CameraConnectionService.DEFAULT_WALK_SECONDS
                : request.durationS();
        return connections.startCalibration(code, seconds);
    }

    @Operation(summary = "Progress or result of a calibration test walk")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @GetMapping("/{code}/calibration/{runId}")
    public CameraViews.CalibrationView calibration(
            @PathVariable @Pattern(regexp = CAMERA_CODE) String code, @PathVariable long runId) {
        return connections.calibration(code, runId);
    }

    @Operation(summary = "Find cameras on the network (unicast ONVIF probe of a /24)")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @PostMapping("/discover")
    public List<CameraViews.DiscoveredCamera> discover(@Valid @RequestBody(required = false) DiscoverRequest request) {
        return connections.discover(request == null ? null : request.subnet());
    }

    @Operation(summary = "Health of the last 7 days: uptime, reconnects and outages")
    @RequiresPermission(Permission.SETTINGS_CAMERAS)
    @GetMapping("/{code}/health")
    public CameraViews.HealthView health(@PathVariable @Pattern(regexp = CAMERA_CODE) String code) {
        return health.health(code);
    }

    private AuditActor actor() {
        var me = currentUser.require();
        return AuditActor.employee(me.employeeId(), me.displayName());
    }
}
