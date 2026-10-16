package com.krztechnologies.attend.common.redis;

import com.krztechnologies.attend.common.transaction.AfterCommit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@link ConfigSignal}s on the Redis channel {@code krz:config} after the database commit, as
 * {@code {v:1, type, camera?, employee_id?, crossing_uid?, version}}. A failed publish is logged and dropped: the
 * worker's 30 s version poll picks the change up anyway, so a Redis hiccup never fails a business change.
 */
@Component
public class ConfigPublisher {

    public static final String CHANNEL = "krz:config";

    private static final Logger LOG = LoggerFactory.getLogger(ConfigPublisher.class);
    private static final int SCHEMA_VERSION = 1;

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final JdbcClient jdbc;

    public ConfigPublisher(StringRedisTemplate redis, JsonMapper json, JdbcClient jdbc) {
        this.redis = redis;
        this.json = json;
        this.jdbc = jdbc;
    }

    /** Publishes once the surrounding transaction has committed (at once without a transaction). */
    public void publishAfterCommit(ConfigSignal signal) {
        AfterCommit.run(() -> publish(signal));
    }

    /**
     * Tells the worker that one employee's gallery entry changed (enrol, erase, deactivate, reactivate, consent
     * withdrawn). The version is read after the commit, once the database triggers have bumped it.
     */
    public void publishGalleryAfterCommit(UUID employeeId) {
        AfterCommit.run(() -> {
            try {
                long version = jdbc.sql("SELECT gallery_version FROM attend.worker_sync_state WHERE id = 1")
                        .query(Long.class)
                        .single();
                publish(ConfigSignal.gallery(employeeId, version));
            } catch (RuntimeException e) {
                LOG.warn(
                        "Could not read the gallery version (the worker's version poll will catch up): {}",
                        e.getMessage());
            }
        });
    }

    private void publish(ConfigSignal signal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", SCHEMA_VERSION);
        payload.put("type", signal.type().wireName());
        if (signal.camera() != null) {
            payload.put("camera", signal.camera());
        }
        if (signal.employeeId() != null) {
            payload.put("employee_id", signal.employeeId().toString());
        }
        if (signal.crossingUid() != null) {
            payload.put("crossing_uid", signal.crossingUid().toString());
        }
        payload.put("version", signal.version());
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(payload));
            LOG.debug("Published {} {} on {}", signal.type().wireName(), signal.version(), CHANNEL);
        } catch (RuntimeException e) {
            LOG.warn(
                    "Could not publish {} on {} (the worker's version poll will catch up): {}",
                    signal.type().wireName(),
                    CHANNEL,
                    e.getMessage());
        }
    }
}
