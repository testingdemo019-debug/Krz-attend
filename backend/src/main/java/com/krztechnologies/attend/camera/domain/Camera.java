package com.krztechnologies.attend.camera.domain;

import com.krztechnologies.attend.camera.service.CameraInput;
import com.krztechnologies.attend.camera.service.FacesVisibleWhen;
import com.krztechnologies.attend.rules.service.CameraMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A camera at an entry point: details, connection (the password AES-GCM encrypted, never returned), geometry (zone and
 * tripwire as jsonb) and the per-camera liveness flag. {@code config_version} goes up on every change the worker must
 * apply. Status and the health metrics belong to {@code CameraHealthService}, which writes them with SQL so a
 * heartbeat never touches {@code version}; here they are written only once, when the camera is added.
 */
@Entity
@Table(name = "camera")
public class Camera {

    /** Settings the worker reads through {@code v_worker_camera} or go2rtc uses (the sub stream). */
    public static final Set<String> RELOAD_FIELDS = Set.of(
            "name", "directionMode", "host", "rtspPort", "onvifPort", "mainPath", "subPath", "username", "enabled");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "entry_point_id", nullable = false)
    private Long entryPointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_mode", nullable = false)
    private CameraMode directionMode;

    @Column(name = "mount_note")
    private String mountNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "faces_visible_when", nullable = false)
    private FacesVisibleWhen facesVisibleWhen;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "rtsp_port", nullable = false)
    private int rtspPort;

    @Column(name = "onvif_port", nullable = false)
    private int onvifPort;

    @Column(name = "main_path", nullable = false)
    private String mainPath;

    @Column(name = "sub_path", nullable = false)
    private String subPath;

    @Column(name = "username")
    private String username;

    @Column(name = "password_enc")
    private byte[] passwordEnc;

    @Column(name = "password_updated_at")
    private Instant passwordUpdatedAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "liveness_enabled", nullable = false)
    private boolean livenessEnabled;

    @Column(name = "zone", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String zone;

    @Column(name = "wire", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String wire;

    @Column(name = "confirm_frames", nullable = false)
    private short confirmFrames;

    @Column(name = "ignore_uturn_sec", nullable = false)
    private short ignoreUturnSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private CameraStatus status;

    @Column(name = "status_since", updatable = false)
    private Instant statusSince;

    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "fps", insertable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal fps;

    @Column(name = "latency_ms", insertable = false, updatable = false)
    private Integer latencyMs;

    @Column(name = "resolution", insertable = false, updatable = false)
    private String resolution;

    @Column(name = "codec", insertable = false, updatable = false)
    private String codec;

    @Column(name = "clock_offset_s", insertable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal clockOffsetSeconds;

    @Column(name = "config_version", nullable = false)
    private long configVersion;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Camera() {}

    /**
     * A new camera with the prototype's starting geometry: CONNECTING until the worker reports it (DISABLED when added
     * with processing off), liveness on, config version 1.
     */
    public Camera(
            String code,
            CameraInput input,
            long entryPointId,
            String zoneJson,
            String wireJson,
            UUID createdBy,
            Instant now) {
        this.code = code;
        apply(input, entryPointId);
        this.zone = zoneJson;
        this.wire = wireJson;
        this.confirmFrames = (short) CameraGeometry.DEFAULT.confirmFrames();
        this.ignoreUturnSec = (short) CameraGeometry.DEFAULT.ignoreUturnSec();
        this.livenessEnabled = true;
        this.status = enabled ? CameraStatus.CONNECTING : CameraStatus.DISABLED;
        this.statusSince = now;
        this.configVersion = 1;
        this.createdBy = createdBy;
    }

    /** Saves the details and connection. */
    public void update(CameraInput input, long entryPointId) {
        apply(input, entryPointId);
    }

    /**
     * The editable details and connection by field name, to see what an edit changed. A change of a
     * {@link #RELOAD_FIELDS} field means the worker ({@code v_worker_camera}) or go2rtc must reload the camera.
     */
    public Map<String, Object> settings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("name", name);
        settings.put("entryPoint", entryPointId);
        settings.put("directionMode", directionMode);
        settings.put("mountNote", mountNote);
        settings.put("facesVisibleWhen", facesVisibleWhen);
        settings.put("model", modelName);
        settings.put("host", host);
        settings.put("rtspPort", rtspPort);
        settings.put("onvifPort", onvifPort);
        settings.put("mainPath", mainPath);
        settings.put("subPath", subPath);
        settings.put("username", username);
        settings.put("enabled", enabled);
        return settings;
    }

    public void setPassword(byte[] encrypted, Instant at) {
        this.passwordEnc = encrypted;
        this.passwordUpdatedAt = at;
    }

    public void setGeometry(String zoneJson, String wireJson, int confirm, int ignoreUturn) {
        this.zone = zoneJson;
        this.wire = wireJson;
        this.confirmFrames = (short) confirm;
        this.ignoreUturnSec = (short) ignoreUturn;
    }

    /** @return whether the flag changed */
    public boolean setLivenessEnabled(boolean on) {
        boolean changed = livenessEnabled != on;
        this.livenessEnabled = on;
        return changed;
    }

    /** The worker reloads this camera when it sees a new config version (krz:config or its 30 s poll). */
    public void bumpConfigVersion() {
        configVersion++;
    }

    private void apply(CameraInput input, long entryPoint) {
        this.name = input.name();
        this.entryPointId = entryPoint;
        this.directionMode = input.directionMode();
        this.mountNote = input.mountNote();
        this.facesVisibleWhen = input.facesVisibleWhen();
        this.modelName = input.model();
        this.host = input.host();
        this.rtspPort = input.rtspPort();
        this.onvifPort = input.onvifPort();
        this.mainPath = input.mainPath();
        this.subPath = input.subPath();
        this.username = input.username();
        this.enabled = input.enabled();
    }

    public RtspUrl mainStream(String password) {
        return RtspUrl.of(host, rtspPort, mainPath, username, password);
    }

    public RtspUrl subStream(String password) {
        return RtspUrl.of(host, rtspPort, subPath, username, password);
    }

    public boolean hasPassword() {
        return passwordEnc != null;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Long getEntryPointId() {
        return entryPointId;
    }

    public CameraMode getDirectionMode() {
        return directionMode;
    }

    public String getMountNote() {
        return mountNote;
    }

    public FacesVisibleWhen getFacesVisibleWhen() {
        return facesVisibleWhen;
    }

    public String getModelName() {
        return modelName;
    }

    public String getHost() {
        return host;
    }

    public int getRtspPort() {
        return rtspPort;
    }

    public int getOnvifPort() {
        return onvifPort;
    }

    public String getMainPath() {
        return mainPath;
    }

    public String getSubPath() {
        return subPath;
    }

    public String getUsername() {
        return username;
    }

    public byte[] getPasswordEnc() {
        return passwordEnc;
    }

    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isLivenessEnabled() {
        return livenessEnabled;
    }

    public String getZone() {
        return zone;
    }

    public String getWire() {
        return wire;
    }

    public int getConfirmFrames() {
        return confirmFrames;
    }

    public int getIgnoreUturnSec() {
        return ignoreUturnSec;
    }

    public CameraStatus getStatus() {
        return status;
    }

    public Instant getStatusSince() {
        return statusSince;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public BigDecimal getFps() {
        return fps;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getResolution() {
        return resolution;
    }

    public String getCodec() {
        return codec;
    }

    public BigDecimal getClockOffsetSeconds() {
        return clockOffsetSeconds;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getVersion() {
        return version;
    }
}
