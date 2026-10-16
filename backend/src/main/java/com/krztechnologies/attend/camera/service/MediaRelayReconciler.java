package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.client.MediaRelayClient;
import com.krztechnologies.attend.camera.client.MediaRelayClient.MediaRelayException;
import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.domain.RtspUrl;
import com.krztechnologies.attend.camera.repository.CameraRepository;
import com.krztechnologies.attend.common.crypto.CryptoService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps go2rtc's streams in line with the enabled cameras: each camera's sub stream is registered under its code when
 * the camera is saved or its password changes, and every 30 s any enabled camera missing from {@code GET /api/streams}
 * is registered again (go2rtc forgets API-added streams when it restarts, its config lives in a tmpfs). URLs are
 * logged masked only.
 */
@Service
public class MediaRelayReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(MediaRelayReconciler.class);

    private final MediaRelayClient relay;
    private final CameraRepository cameras;
    private final CryptoService crypto;
    private final TimeService time;
    private volatile RelayCheck lastCheck;

    public MediaRelayReconciler(
            MediaRelayClient relay, CameraRepository cameras, CryptoService crypto, TimeService time) {
        this.relay = relay;
        this.cameras = cameras;
        this.crypto = crypto;
        this.time = time;
    }

    /**
     * The last reconciliation's view of go2rtc, for Settings → System.
     *
     * @param streams the streams go2rtc had (0 when it was not reachable)
     */
    public record RelayCheck(Instant at, boolean reachable, int streams) {}

    /** Empty until the first reconciliation (15 s after start). */
    public Optional<RelayCheck> lastCheck() {
        return Optional.ofNullable(lastCheck);
    }

    /** Registers the camera's stream after the commit, on a virtual thread so a slow go2rtc never delays the user. */
    void registerAfterCommit(Camera camera) {
        if (!camera.isEnabled() || !registrable(camera)) {
            return;
        }
        String code = camera.getCode();
        RtspUrl source = subStream(camera);
        AfterCommit.run(() -> Thread.startVirtualThread(() -> register(code, source)));
    }

    /** Every 30 s, on one instance (ShedLock locks only methods that return nothing). */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    @SchedulerLock(name = "media-relay-reconciler", lockAtMostFor = "PT2M")
    public void scheduledReconcile() {
        reconcile();
    }

    /** Registers every enabled camera go2rtc does not have; returns how many were registered. */
    public int reconcile() {
        Set<String> present;
        try {
            present = relay.streamNames();
        } catch (MediaRelayException e) {
            LOG.warn("Media relay reconciliation skipped: {}", e.getMessage());
            lastCheck = new RelayCheck(time.now(), false, 0);
            return 0;
        }
        lastCheck = new RelayCheck(time.now(), true, present.size());
        int registered = 0;
        for (Camera camera : cameras.findAllByEnabledTrueOrderByCodeAsc()) {
            boolean missing = !present.contains(camera.getCode()) && registrable(camera);
            if (missing && register(camera.getCode(), subStream(camera))) {
                registered++;
            }
        }
        return registered;
    }

    /** A camera with a user name needs its password before go2rtc can open it. */
    private static boolean registrable(Camera camera) {
        return camera.getUsername() == null || camera.hasPassword();
    }

    private RtspUrl subStream(Camera camera) {
        String password = camera.hasPassword()
                ? new String(crypto.decrypt(camera.getPasswordEnc()), StandardCharsets.UTF_8)
                : null;
        return camera.subStream(password);
    }

    private boolean register(String code, RtspUrl source) {
        try {
            relay.register(code, source);
            LOG.info("Registered media relay stream {} → {}", code, source.masked());
            return true;
        } catch (MediaRelayException e) {
            LOG.warn("Media relay stream {} not registered (retried within 30 s): {}", code, e.getMessage());
            return false;
        }
    }
}
