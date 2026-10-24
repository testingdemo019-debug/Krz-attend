package com.krztechnologies.attend.camera.domain;

import com.krztechnologies.attend.rules.service.CameraMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where a camera detects people and how it decides IN/OUT: the counting method (D-120), the detection zone (a
 * normalised polygon {@code [[x,y],…]} of at least 3 points; empty = the whole image) and the method's own drawing and
 * settings. Line crossing uses the tripwire, how many frames past the line confirm a crossing and the U-turn window;
 * doorway zone uses the door area (a polygon around the door opening). Both drawings are kept whichever method is on.
 *
 * @param zone the zone polygon, empty when the whole image is used
 * @param wire the tripwire, null when none is drawn (a line-crossing camera needs one unless in MONITOR mode)
 * @param doorZone the door area, empty when none is drawn (a doorway-zone camera needs one)
 */
public record CameraGeometry(
        CountingMethod countingMethod,
        List<List<Double>> zone,
        Wire wire,
        List<List<Double>> doorZone,
        int confirmFrames,
        int ignoreUturnSec) {

    public static final int MIN_ZONE_POINTS = 3;

    /** The prototype's starting zone, tripwire and timings for a new camera. */
    public static final CameraGeometry DEFAULT = new CameraGeometry(
            CountingMethod.LINE_CROSSING,
            List.of(List.of(0.16, 0.30), List.of(0.84, 0.30), List.of(0.98, 0.99), List.of(0.02, 0.99)),
            Wire.DEFAULT,
            List.of(),
            3,
            5);

    public CameraGeometry {
        // Not List.copyOf: a request may hold null points, which the service reports as a validation error.
        zone = zone == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(zone));
        doorZone = doorZone == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(doorZone));
    }

    /** The same geometry with the tripwire's inside on the other side ("Flip inside direction"). */
    public CameraGeometry flipped() {
        return new CameraGeometry(countingMethod, zone, wire.flipped(), doorZone, confirmFrames, ignoreUturnSec);
    }

    /**
     * The summary in audit entries: the prototype's "IN + OUT · confirm 3 frames · U-turn 5 s · inside ↓" for a line,
     * "IN + OUT · doorway zone" for a door area.
     */
    public String summary(CameraMode mode) {
        if (countingMethod == CountingMethod.DOORWAY_ZONE) {
            return mode.shortLabel() + " · doorway zone";
        }
        String side = wire == null ? "no tripwire" : wire.inSide() > 0 ? "inside ↓" : "inside ↑";
        return mode.shortLabel() + " · confirm " + confirmFrames + " frames · U-turn " + ignoreUturnSec + " s · "
                + side;
    }
}
