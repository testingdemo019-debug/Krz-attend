package com.krztechnologies.attend.camera.domain;

import com.krztechnologies.attend.rules.service.CameraMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where a camera detects people and decides IN/OUT: the detection zone (a normalised polygon {@code [[x,y],…]} of at
 * least 3 points; empty = the whole image), the tripwire, how many frames past the line confirm a crossing and the
 * U-turn window.
 *
 * @param zone the zone polygon, empty when the whole image is used
 * @param wire the tripwire, null only for a camera in MONITOR mode
 */
public record CameraGeometry(List<List<Double>> zone, Wire wire, int confirmFrames, int ignoreUturnSec) {

    public static final int MIN_ZONE_POINTS = 3;

    /** The prototype's starting zone, tripwire and timings for a new camera. */
    public static final CameraGeometry DEFAULT = new CameraGeometry(
            List.of(List.of(0.16, 0.30), List.of(0.84, 0.30), List.of(0.98, 0.99), List.of(0.02, 0.99)),
            Wire.DEFAULT,
            3,
            5);

    public CameraGeometry {
        // Not List.copyOf: a request may hold null points, which the service reports as a validation error.
        zone = zone == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(zone));
    }

    /** The prototype's summary in audit entries: "IN + OUT · confirm 3 frames · U-turn 5 s · inside ↓". */
    public String summary(CameraMode mode) {
        String side = wire == null ? "no tripwire" : wire.inSide() > 0 ? "inside ↓" : "inside ↑";
        return mode.shortLabel() + " · confirm " + confirmFrames + " frames · U-turn " + ignoreUturnSec + " s · "
                + side;
    }
}
