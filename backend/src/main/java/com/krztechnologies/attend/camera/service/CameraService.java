package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.domain.CameraGeometry;
import com.krztechnologies.attend.camera.domain.CountingMethod;
import com.krztechnologies.attend.camera.repository.CameraRepository;
import com.krztechnologies.attend.camera.repository.EntryPointQueries;
import com.krztechnologies.attend.camera.repository.EntryPointQueries.EntryPointRow;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.redis.ConfigSignal;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.rules.service.CameraMode;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Cameras: add and edit cameras (code CAM-nn, at an entry point), set the password (AES-GCM encrypted,
 * never returned), choose the counting method (D-120) and edit its drawing (the tripwire or the door area) and the
 * zone, flip the inside direction and switch liveness. Every change the worker
 * must apply bumps {@code config_version} and publishes {@code krz:config camera} after the commit; connection changes
 * also re-register the go2rtc stream. Every change is audited (category Cameras; liveness under Recognition).
 */
@Service
public class CameraService {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final CameraRepository cameras;
    private final EntryPointQueries entryPoints;
    private final GeometryJson geometry;
    private final CryptoService crypto;
    private final IdGenerator ids;
    private final AuditService audit;
    private final ConfigPublisher config;
    private final MediaRelayReconciler relay;
    private final CameraHealthService health;
    private final TimeService time;
    private final Validator validator;
    private final EntityManager entityManager;

    public CameraService(
            CameraRepository cameras,
            EntryPointQueries entryPoints,
            GeometryJson geometry,
            CryptoService crypto,
            IdGenerator ids,
            AuditService audit,
            ConfigPublisher config,
            MediaRelayReconciler relay,
            CameraHealthService health,
            TimeService time,
            Validator validator,
            EntityManager entityManager) {
        this.cameras = cameras;
        this.entryPoints = entryPoints;
        this.geometry = geometry;
        this.crypto = crypto;
        this.ids = ids;
        this.audit = audit;
        this.config = config;
        this.relay = relay;
        this.health = health;
        this.time = time;
        this.validator = validator;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<CameraViews.CameraView> list() {
        List<Camera> all = cameras.findAllByOrderByCodeAsc();
        Map<Long, EntryPointRow> points =
                entryPoints.findByIds(all.stream().map(Camera::getEntryPointId).collect(Collectors.toSet()));
        Map<Long, BigDecimal> uptime = health.uptimeLastWeek();
        return all.stream()
                .map(c -> view(c, points.get(c.getEntryPointId()), uptime.get(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CameraViews.CameraView get(String code) {
        return view(find(code));
    }

    /** Whether any camera exists (the dev bootstrap seeds CAM-01 only into an empty table). */
    @Transactional(readOnly = true)
    public boolean anyCamera() {
        return cameras.count() > 0;
    }

    /** Adds a camera with the prototype's starting zone and tripwire; the password is set separately. */
    @Transactional
    public CameraViews.CameraView create(CameraInput input, AuditActor actor) {
        validate(input);
        EntryPointRow entryPoint = entryPoint(input.entryPoint(), null);
        Camera camera = cameras.saveAndFlush(new Camera(
                ids.next(CodeType.CAMERA),
                input,
                entryPoint.id(),
                geometry.zoneColumn(CameraGeometry.DEFAULT),
                geometry.wireColumn(CameraGeometry.DEFAULT),
                actor.employeeId(),
                time.now()));
        audit.record(
                actor,
                AuditCategory.CAMERAS,
                "Added camera",
                camera.getName(),
                null,
                camera.getCode() + " · " + camera.getDirectionMode().shortLabel(),
                null);
        config.publishAfterCommit(ConfigSignal.camera(camera.getCode(), camera.getConfigVersion()));
        relay.registerAfterCommit(camera);
        return view(camera, entryPoint, null);
    }

    /** Saves details and connection; 409 STALE_VERSION when someone else saved in the meantime. */
    @Transactional
    public CameraViews.CameraView update(String code, CameraInput input) {
        validate(input);
        Camera camera = find(code);
        checkVersion(camera, input.version());
        EntryPointRow entryPoint = entryPoint(input.entryPoint(), camera.getEntryPointId());
        CameraGeometry shape = geometry.of(camera);
        if (needsTripwire(shape.countingMethod(), input.directionMode()) && shape.wire() == null) {
            throw tripwireRequired();
        }
        Map<String, Object> before = camera.settings();
        String beforeSummary = shape.summary(camera.getDirectionMode());
        boolean wasEnabled = camera.isEnabled();
        camera.update(input, entryPoint.id());
        List<String> changed = changes(before, camera.settings());
        if (changed.isEmpty()) {
            return view(camera, entryPoint, null);
        }
        boolean reload = changed.stream().anyMatch(Camera.RELOAD_FIELDS::contains);
        if (reload) {
            camera.bumpConfigVersion();
        }
        cameras.saveAndFlush(camera);
        if (wasEnabled != camera.isEnabled()) {
            health.processingChanged(camera, time.now());
            entityManager.refresh(camera);
        }
        audit.record(
                AuditCategory.CAMERAS,
                "Updated camera",
                camera.getName(),
                beforeSummary,
                shape.summary(camera.getDirectionMode()),
                Map.of("changed", changed));
        if (reload) {
            config.publishAfterCommit(ConfigSignal.camera(camera.getCode(), camera.getConfigVersion()));
            relay.registerAfterCommit(camera);
        }
        return view(camera, entryPoint, null);
    }

    /** Stores the camera password encrypted with the active key; it is never returned or logged. */
    @Transactional
    public CameraViews.CameraView setPassword(String code, String password, AuditActor actor) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new Errors.BusinessRule(
                    "VALIDATION",
                    "Use at least 8 characters.",
                    List.of(new ErrorResponse.FieldError("password", "Use at least 8 characters.")));
        }
        Camera camera = find(code);
        camera.setPassword(crypto.encrypt(password.getBytes(StandardCharsets.UTF_8)), time.now());
        camera.bumpConfigVersion();
        cameras.saveAndFlush(camera);
        audit.record(
                actor,
                AuditCategory.CAMERAS,
                "Changed camera password",
                camera.getName(),
                null,
                "Updated (value not logged)",
                null);
        config.publishAfterCommit(ConfigSignal.camera(camera.getCode(), camera.getConfigVersion()));
        relay.registerAfterCommit(camera);
        return view(camera);
    }

    /** Saves the counting method, the detection zone, the tripwire or door area, the confirm frames and the U-turn
     * window. */
    @Transactional
    public CameraViews.CameraView setGeometry(String code, CameraGeometry shape, int version) {
        Camera camera = find(code);
        checkVersion(camera, version);
        checkGeometry(shape, camera.getDirectionMode());
        return applyGeometry(camera, shape);
    }

    /** "Flip inside direction": IN becomes OUT and back. */
    @Transactional
    public CameraViews.CameraView flip(String code, int version) {
        Camera camera = find(code);
        checkVersion(camera, version);
        CameraGeometry shape = geometry.of(camera);
        if (shape.countingMethod() != CountingMethod.LINE_CROSSING) {
            throw new Errors.BusinessRule(
                    "NOT_LINE_CROSSING", "This camera counts by doorway zone; it has no tripwire to flip.");
        }
        if (shape.wire() == null) {
            throw tripwireRequired();
        }
        return applyGeometry(camera, shape.flipped());
    }

    /** The per-camera liveness check (Settings → Recognition → Liveness), audited under Recognition. */
    @Transactional
    public CameraViews.CameraLiveness setLiveness(String code, boolean enabled) {
        Camera camera = find(code);
        if (camera.setLivenessEnabled(enabled)) {
            camera.bumpConfigVersion();
            cameras.saveAndFlush(camera);
            audit.record(
                    AuditCategory.RECOGNITION,
                    "Updated Recognition",
                    camera.getName(),
                    "Liveness " + (enabled ? "off" : "on"),
                    "Liveness " + (enabled ? "on" : "off"),
                    null);
            config.publishAfterCommit(ConfigSignal.camera(camera.getCode(), camera.getConfigVersion()));
        }
        return liveness(camera);
    }

    @Transactional(readOnly = true)
    public List<CameraViews.CameraLiveness> livenessFlags() {
        return cameras.findAllByOrderByCodeAsc().stream()
                .map(CameraService::liveness)
                .toList();
    }

    private CameraViews.CameraView applyGeometry(Camera camera, CameraGeometry shape) {
        CameraGeometry before = geometry.of(camera);
        if (before.equals(shape)) {
            return view(camera);
        }
        camera.setGeometry(
                shape.countingMethod(),
                geometry.zoneColumn(shape),
                geometry.wireColumn(shape),
                geometry.doorZoneColumn(shape),
                shape.confirmFrames(),
                shape.ignoreUturnSec());
        camera.bumpConfigVersion();
        cameras.saveAndFlush(camera);
        audit.record(
                AuditCategory.CAMERAS,
                "Updated camera",
                camera.getName(),
                before.summary(camera.getDirectionMode()),
                shape.summary(camera.getDirectionMode()),
                Map.of("changed", geometryChanges(before, shape)));
        config.publishAfterCommit(ConfigSignal.camera(camera.getCode(), camera.getConfigVersion()));
        return view(camera);
    }

    private static void checkGeometry(CameraGeometry shape, CameraMode mode) {
        List<ErrorResponse.FieldError> problems = new ArrayList<>();
        if (!shape.zone().isEmpty() && shape.zone().size() < CameraGeometry.MIN_ZONE_POINTS) {
            problems.add(new ErrorResponse.FieldError("zone", "A detection zone needs at least 3 points."));
        }
        if (!shape.zone().stream().allMatch(CameraService::isNormalisedPoint)) {
            problems.add(new ErrorResponse.FieldError("zone", "Each point is [x, y] with values from 0 to 1."));
        }
        if (!shape.doorZone().isEmpty() && shape.doorZone().size() < CameraGeometry.MIN_ZONE_POINTS) {
            problems.add(new ErrorResponse.FieldError("doorZone", "A door area needs at least 3 points."));
        }
        if (!shape.doorZone().stream().allMatch(CameraService::isNormalisedPoint)) {
            problems.add(new ErrorResponse.FieldError("doorZone", "Each point is [x, y] with values from 0 to 1."));
        }
        if (shape.wire() != null) {
            if (Math.abs(shape.wire().inSide()) != 1) {
                problems.add(new ErrorResponse.FieldError("wire.inSide", "Must be 1 or -1."));
            }
            if (shape.wire().a().equals(shape.wire().b())) {
                problems.add(new ErrorResponse.FieldError("wire", "The tripwire needs two different points."));
            }
        }
        if (!problems.isEmpty()) {
            throw new Errors.BusinessRule("VALIDATION", "Some fields are not valid.", problems);
        }
        if (needsTripwire(shape.countingMethod(), mode) && shape.wire() == null) {
            throw tripwireRequired();
        }
        if (shape.countingMethod() == CountingMethod.DOORWAY_ZONE
                && shape.doorZone().isEmpty()) {
            throw new Errors.BusinessRule("DOOR_AREA_REQUIRED", "Draw the door area — it decides IN and OUT");
        }
    }

    /** A line-crossing camera needs its tripwire unless it only monitors; a doorway-zone camera needs none. */
    private static boolean needsTripwire(CountingMethod method, CameraMode mode) {
        return method == CountingMethod.LINE_CROSSING && mode != CameraMode.MONITOR;
    }

    private static boolean isNormalisedPoint(List<Double> point) {
        return point != null && point.size() == 2 && point.stream().allMatch(v -> v != null && v >= 0 && v <= 1);
    }

    private static List<String> geometryChanges(CameraGeometry before, CameraGeometry after) {
        List<String> changed = new ArrayList<>();
        if (before.countingMethod() != after.countingMethod()) {
            changed.add("countingMethod");
        }
        if (!before.zone().equals(after.zone())) {
            changed.add("zone");
        }
        if (!before.doorZone().equals(after.doorZone())) {
            changed.add("doorZone");
        }
        if (!Objects.equals(before.wire(), after.wire())) {
            changed.add("wire");
        }
        if (before.confirmFrames() != after.confirmFrames()) {
            changed.add("confirmFrames");
        }
        if (before.ignoreUturnSec() != after.ignoreUturnSec()) {
            changed.add("ignoreUturnSec");
        }
        return changed;
    }

    private static List<String> changes(Map<String, Object> before, Map<String, Object> after) {
        return after.keySet().stream()
                .filter(key -> !Objects.equals(before.get(key), after.get(key)))
                .toList();
    }

    private void validate(CameraInput input) {
        Set<ConstraintViolation<CameraInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new Errors.BusinessRule(
                    "VALIDATION",
                    "Some fields are not valid.",
                    violations.stream()
                            .map(v -> new ErrorResponse.FieldError(
                                    v.getPropertyPath().toString(), v.getMessage()))
                            .toList());
        }
    }

    /** The entry point by code; it must be active unless the camera already belongs to it. */
    private EntryPointRow entryPoint(String code, Long currentId) {
        return entryPoints
                .findByCode(code)
                .filter(ep -> ep.isActive() || Objects.equals(ep.id(), currentId))
                .orElseThrow(() -> new Errors.BusinessRule(
                        "ENTRY_POINT_NOT_FOUND",
                        "Choose an active entry point.",
                        List.of(new ErrorResponse.FieldError("entryPoint", "Choose an active entry point."))));
    }

    private Camera find(String code) {
        return cameras.findByCode(code).orElseThrow(() -> new Errors.NotFound("Camera not found."));
    }

    private static void checkVersion(Camera camera, Integer version) {
        if (version == null) {
            throw new Errors.BadRequest("VERSION_REQUIRED", "Send the version of the camera you edited.");
        }
        if (!camera.getVersion().equals(version)) {
            throw new Errors.Conflict("STALE_VERSION", "Someone else changed this camera. Reload to see the latest.");
        }
    }

    private static Errors.BusinessRule tripwireRequired() {
        return new Errors.BusinessRule("TRIPWIRE_REQUIRED", "Add a tripwire — it decides IN and OUT");
    }

    private CameraViews.CameraView view(Camera camera) {
        EntryPointRow entryPoint =
                entryPoints.findByIds(Set.of(camera.getEntryPointId())).get(camera.getEntryPointId());
        return view(camera, entryPoint, health.uptimeLastWeek().get(camera.getId()));
    }

    private CameraViews.CameraView view(Camera camera, EntryPointRow entryPoint, BigDecimal uptime) {
        CameraGeometry shape = geometry.of(camera);
        Instant passwordUpdatedAt = camera.getPasswordUpdatedAt();
        return new CameraViews.CameraView(
                camera.getCode(),
                camera.getName(),
                entryPoint == null
                        ? null
                        : new CameraViews.EntryPointRef(
                                entryPoint.code(),
                                entryPoint.name(),
                                entryPoint.locationCode(),
                                entryPoint.locationName()),
                camera.getDirectionMode(),
                camera.getMountNote(),
                camera.getFacesVisibleWhen(),
                camera.getModelName(),
                camera.getHost(),
                camera.getRtspPort(),
                camera.getOnvifPort(),
                camera.getMainPath(),
                camera.getSubPath(),
                camera.getUsername(),
                camera.hasPassword(),
                passwordUpdatedAt,
                camera.mainStream(null).masked(),
                camera.subStream(null).masked(),
                camera.isEnabled(),
                camera.isLivenessEnabled(),
                shape.countingMethod(),
                shape.zone(),
                shape.wire(),
                shape.doorZone(),
                shape.confirmFrames(),
                shape.ignoreUturnSec(),
                camera.getStatus(),
                camera.getStatusSince(),
                camera.getLastSeenAt(),
                camera.getFps(),
                camera.getLatencyMs(),
                camera.getResolution(),
                camera.getCodec(),
                camera.getClockOffsetSeconds(),
                uptime,
                camera.getConfigVersion(),
                camera.getVersion());
    }

    private static CameraViews.CameraLiveness liveness(Camera camera) {
        return new CameraViews.CameraLiveness(camera.getCode(), camera.getName(), camera.isLivenessEnabled());
    }
}
