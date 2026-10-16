package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.domain.CameraGeometry;
import com.krztechnologies.attend.camera.domain.Wire;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Converts a camera's zone and tripwire between the jsonb columns and {@link CameraGeometry}. */
@Component
class GeometryJson {

    private static final TypeReference<List<List<Double>>> POLYGON = new TypeReference<>() {};

    private final JsonMapper json;

    GeometryJson(JsonMapper json) {
        this.json = json;
    }

    CameraGeometry of(Camera camera) {
        List<List<Double>> zone = camera.getZone() == null ? List.of() : json.readValue(camera.getZone(), POLYGON);
        Wire wire = camera.getWire() == null ? null : json.readValue(camera.getWire(), Wire.class);
        return new CameraGeometry(zone, wire, camera.getConfirmFrames(), camera.getIgnoreUturnSec());
    }

    /** The zone column: null for "the whole image". */
    String zoneColumn(CameraGeometry geometry) {
        return geometry.zone().isEmpty() ? null : json.writeValueAsString(geometry.zone());
    }

    String wireColumn(CameraGeometry geometry) {
        return geometry.wire() == null ? null : json.writeValueAsString(geometry.wire());
    }
}
