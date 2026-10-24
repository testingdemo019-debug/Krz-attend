package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.camera.domain.Camera;
import com.krztechnologies.attend.camera.domain.CameraGeometry;
import com.krztechnologies.attend.camera.domain.Wire;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Converts a camera's zone, tripwire and door area between the jsonb columns and {@link CameraGeometry}. */
@Component
class GeometryJson {

    private static final TypeReference<List<List<Double>>> POLYGON = new TypeReference<>() {};

    private final JsonMapper json;

    GeometryJson(JsonMapper json) {
        this.json = json;
    }

    CameraGeometry of(Camera camera) {
        Wire wire = camera.getWire() == null ? null : json.readValue(camera.getWire(), Wire.class);
        return new CameraGeometry(
                camera.getCountingMethod(),
                polygon(camera.getZone()),
                wire,
                polygon(camera.getDoorZone()),
                camera.getConfirmFrames(),
                camera.getIgnoreUturnSec());
    }

    /** The zone column: null for "the whole image". */
    String zoneColumn(CameraGeometry geometry) {
        return polygonColumn(geometry.zone());
    }

    /** The door area column: null when none is drawn. */
    String doorZoneColumn(CameraGeometry geometry) {
        return polygonColumn(geometry.doorZone());
    }

    String wireColumn(CameraGeometry geometry) {
        return geometry.wire() == null ? null : json.writeValueAsString(geometry.wire());
    }

    private List<List<Double>> polygon(String column) {
        return column == null ? List.of() : json.readValue(column, POLYGON);
    }

    private String polygonColumn(List<List<Double>> polygon) {
        return polygon.isEmpty() ? null : json.writeValueAsString(polygon);
    }
}
