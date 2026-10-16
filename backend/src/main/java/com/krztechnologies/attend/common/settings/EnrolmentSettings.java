package com.krztechnologies.attend.common.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Settings → Enrolment &amp; quality (app_setting key {@code enrolment}), R15.
 *
 * @param minGood accepted captures needed to save a face profile
 * @param minQuality minimum capture quality (0–100)
 * @param minEyePx minimum distance between the eyes in pixels
 * @param dupThreshold similarity from which another enrolled employee is reported as a lookalike
 * @param lockFailures liveness failures within {@code lockWindowMin} that lock enrolment for {@code lockMinutes}
 */
public record EnrolmentSettings(
        @Min(1) @Max(10) int minGood,
        @Min(0) @Max(100) int minQuality,
        @Min(20) @Max(400) int minEyePx,
        boolean livenessRequired,
        boolean allowUpload,
        @DecimalMin("0.10") @DecimalMax("0.90") double dupThreshold,
        @Min(1) @Max(20) int lockFailures,
        @Min(1) @Max(60) int lockWindowMin,
        @Min(1) @Max(1440) int lockMinutes) {}
