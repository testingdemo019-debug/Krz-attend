package com.krztechnologies.attend.recognition.service;

/**
 * The values of the active threshold profile other modules read (the review reasons, the quality chips): the
 * service-level view of {@code ThresholdValues}.
 *
 * @param accept the face similarity that auto-accepts
 * @param reviewBand the band below {@code accept} that goes to review as uncertain
 * @param margin the minimum gap between the top two candidates
 * @param minEyePx face quality gate: minimum eye distance in pixels
 * @param maxYaw face quality gate: maximum head turn in degrees
 * @param sharpMin face quality gate: minimum sharpness
 * @param reidThreshold the Re-ID similarity that auto-accepts
 */
public record ActiveThresholds(
        double accept,
        double reviewBand,
        double margin,
        int minEyePx,
        int maxYaw,
        int sharpMin,
        double reidThreshold) {}
