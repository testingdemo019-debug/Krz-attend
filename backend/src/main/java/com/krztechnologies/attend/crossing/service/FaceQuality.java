package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.recognition.service.ActiveThresholds;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A face crop's quality against the active quality gate: the prototype's quality chips (Face size, Sharpness, Pose,
 * Lighting), built from the crossing's quality JSON ({@code eye_px}, {@code yaw}, {@code pitch}, {@code sharp},
 * {@code quality}, {@code light}).
 *
 * @param pose the larger of |yaw| and |pitch| in whole degrees, checked against {@code maxYaw}
 * @param light 0–100 (100 = evenly exposed); null for crossings recorded before the worker sent it
 */
public record FaceQuality(
        int eyePx,
        boolean eyePxOk,
        int sharp,
        boolean sharpOk,
        int pose,
        boolean poseOk,
        @Nullable Integer light,
        @Nullable Boolean lightOk) {

    /** The prototype's lighting threshold. */
    static final int MIN_LIGHT = 50;

    /** The chips of a quality JSON, or null without one. */
    public static @Nullable FaceQuality of(@Nullable JsonNode quality, ActiveThresholds gate) {
        if (quality == null || !quality.isObject()) {
            return null;
        }
        int eyePx = quality.path("eye_px").asInt(0);
        int sharp = quality.path("sharp").asInt(0);
        int pose = (int) Math.round(Math.max(
                Math.abs(quality.path("yaw").asDouble(0)),
                Math.abs(quality.path("pitch").asDouble(0))));
        Integer light = quality.path("light").isNumber() ? quality.path("light").asInt() : null;
        return new FaceQuality(
                eyePx,
                eyePx >= gate.minEyePx(),
                sharp,
                sharp >= gate.sharpMin(),
                pose,
                pose <= gate.maxYaw(),
                light,
                light == null ? null : light >= MIN_LIGHT);
    }
}
