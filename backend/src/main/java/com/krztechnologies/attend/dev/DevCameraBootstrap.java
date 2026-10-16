package com.krztechnologies.attend.dev;

import com.krztechnologies.attend.camera.service.CameraInput;
import com.krztechnologies.attend.camera.service.CameraService;
import com.krztechnologies.attend.camera.service.FacesVisibleWhen;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.rules.service.CameraMode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Development only ({@code DEV_CAMERA_SEED=true}): when no camera exists, creates CAM-01 "Main entrance" at the dev
 * seed's location VLR (its first active entry point) from {@code CAMERA_01_*} in deploy/.env, with the password
 * encrypted like any other. In production cameras come only from Settings → Cameras. When the camera user is not set
 * yet, or anything else is missing, it logs why and skips; it never stops the application.
 */
@Component
@Profile("dev")
@ConditionalOnBooleanProperty(name = "krz.dev.camera-seed")
public class DevCameraBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(DevCameraBootstrap.class);
    private static final String LOCATION = "VLR";

    private final CameraService cameras;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Environment environment;

    public DevCameraBootstrap(
            CameraService cameras, JdbcClient jdbc, TransactionTemplate transactions, Environment environment) {
        this.cameras = cameras;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.environment = environment;
    }

    /** {@code CAMERA_01_*} (bound from {@code krz.dev.camera-01}); {@link #toString()} leaves the password out. */
    public record Camera01(
            String host,
            Integer rtspPort,
            Integer onvifPort,
            String mainPath,
            String subPath,
            String username,
            String password) {

        @Override
        public String toString() {
            return "Camera01[host=" + host + ", rtspPort=" + rtspPort + "]";
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            seedIfEmpty();
        } catch (RuntimeException e) {
            LOG.warn("DEV_CAMERA_SEED: CAM-01 was not created: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (cameras.anyCamera()) {
            LOG.info("DEV_CAMERA_SEED: a camera exists already; nothing to seed");
            return;
        }
        Camera01 camera = Binder.get(environment)
                .bind("krz.dev.camera-01", Camera01.class)
                .orElse(null);
        if (camera == null || isBlank(camera.host()) || isBlank(camera.username()) || isBlank(camera.password())) {
            LOG.info("DEV_CAMERA_SEED: CAMERA_01_HOST, CAMERA_01_USERNAME or CAMERA_01_PASSWORD is empty in "
                    + "deploy/.env; CAM-01 not created (set them, or add the camera in Settings → Cameras)");
            return;
        }
        Optional<String> entryPoint =
                jdbc.sql("""
                        SELECT ep.code FROM attend.entry_point ep JOIN attend.location l ON l.id = ep.location_id
                         WHERE l.code = :location AND ep.status = 'ACTIVE' ORDER BY ep.code LIMIT 1
                        """).param("location", LOCATION).query(String.class).optional();
        if (entryPoint.isEmpty()) {
            LOG.info(
                    "DEV_CAMERA_SEED: location {} has no active entry point (dev seed missing); CAM-01 not created",
                    LOCATION);
            return;
        }
        CameraInput input = new CameraInput(
                "Main entrance",
                entryPoint.get(),
                CameraMode.BOTH,
                "Inside, facing the door",
                FacesVisibleWhen.ENTERING,
                null,
                camera.host(),
                camera.rtspPort(),
                camera.onvifPort(),
                camera.mainPath(),
                camera.subPath(),
                camera.username(),
                true,
                null);
        String code = transactions.execute(status -> {
            String created = cameras.create(input, AuditActor.SYSTEM).code();
            cameras.setPassword(created, camera.password(), AuditActor.SYSTEM);
            return created;
        });
        LOG.info(
                "DEV_CAMERA_SEED: created {} at {} {} from CAMERA_01_* (password stored encrypted)",
                code,
                LOCATION,
                entryPoint.get());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
