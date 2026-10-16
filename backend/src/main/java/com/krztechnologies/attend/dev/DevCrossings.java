package com.krztechnologies.attend.dev;

import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.camera.service.LiveViewService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.crossing.service.CrossingMessage;
import com.krztechnologies.attend.crossing.service.CrossingStreamConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Dev only: writes synthetic crossing messages into {@code krz:crossings} as the worker would (Redis contract §1), so
 * they take the real path: stream consumer, rules, review, HRMS outbox, realtime events. A known person comes in by
 * face and goes out by back-view Re-ID; without a person it is an unknown face.
 */
@Service
@Profile("dev")
public class DevCrossings {

    private final StringRedisTemplate redis;
    private final LiveViewService cameras;
    private final TimeService time;

    public DevCrossings(StringRedisTemplate redis, LiveViewService cameras, TimeService time) {
        this.redis = redis;
        this.cameras = cameras;
        this.time = time;
    }

    /**
     * @param camera a camera code, or null for the first enabled camera
     * @param employeeId the person crossing, or null for an unknown face
     * @return the message's {@code crossing_uid}
     */
    public UUID inject(String camera, String direction, UUID employeeId) {
        String cameraCode = camera != null ? camera : firstCamera();
        UUID uid = UUID.randomUUID();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("v", CrossingMessage.VERSION);
        fields.put("crossing_uid", uid.toString());
        fields.put("camera_code", cameraCode);
        fields.put("ts", time.now().toString());
        fields.put("direction", direction);
        fields.put("u_turn", "false");
        fields.put("liveness", "PASSED");
        if (employeeId == null) {
            fields.put("decision_hint", "UNKNOWN");
            fields.put("method", "NONE");
            fields.put("employee_id", "");
            fields.put("unknown_cluster", "");
        } else if ("IN".equals(direction)) {
            fields.put("decision_hint", "AUTO");
            fields.put("method", "FACE");
            fields.put("employee_id", employeeId.toString());
            fields.put("score", "0.8200");
        } else {
            fields.put("decision_hint", "AUTO");
            fields.put("method", "REID");
            fields.put("employee_id", employeeId.toString());
            fields.put("reid_score", "0.7400");
        }
        fields.put("model_version", "dev-synthetic");
        fields.put("worker_id", "dev");
        fields.put("trace_id", "dev" + uid.toString().replace("-", "").substring(0, 13));
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(CrossingStreamConsumer.STREAM));
        return uid;
    }

    private String firstCamera() {
        return cameras.tiles().stream()
                .map(CameraViews.LiveTile::code)
                .findFirst()
                .orElseThrow(() -> new Errors.Conflict("NO_CAMERA", "Add and enable a camera first."));
    }
}
