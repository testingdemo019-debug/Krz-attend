package com.krztechnologies.attend.recognition.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The values of a threshold profile (R14), stored as {@code threshold_profile.profile_values} with these exact keys
 * (the worker reads them through {@code v_worker_recognition}). Ranges follow the prototype's sliders where it has
 * one.
 *
 * @param accept the cosine similarity needed to auto-accept a face
 * @param reviewBand the band below {@code accept} that goes to review as uncertain
 * @param margin the minimum gap between the top-1 and top-2 employee
 * @param agreeK how many of the last {@code agreeN} face votes of a track must agree
 * @param minEyePx face quality gate: minimum eye distance in pixels
 * @param maxYaw face quality gate: maximum head turn in degrees
 * @param sharpMin face quality gate: minimum sharpness
 * @param qualityMin face quality gate: minimum combined quality score
 * @param recheckGrow re-run recognition when the face box grows by this many percent
 * @param reidOn back-view Re-ID for OUT crossings without a usable face
 * @param reidThreshold the Re-ID similarity needed to auto-accept
 */
public record ThresholdValues(
        @NotNull @DecimalMin("0.40") @DecimalMax("0.90") Double accept,
        @NotNull @DecimalMin("0.02") @DecimalMax("0.20") Double reviewBand,
        @NotNull @DecimalMin("0.02") @DecimalMax("0.25") Double margin,
        @NotNull @Min(1) @Max(10) Integer agreeK,
        @NotNull @Min(1) @Max(10) Integer agreeN,
        @NotNull @Min(20) @Max(60) Integer minEyePx,
        @NotNull @Min(10) @Max(45) Integer maxYaw,
        @NotNull @Min(10) @Max(90) Integer sharpMin,
        @NotNull @Min(0) @Max(100) Integer qualityMin,
        @NotNull @Min(5) @Max(100) Integer recheckGrow,
        @NotNull Boolean reidOn,
        @NotNull @DecimalMin("0.50") @DecimalMax("0.95") Double reidThreshold) {}
