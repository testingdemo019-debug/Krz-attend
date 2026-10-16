package com.krztechnologies.attend.recognition.service;

import java.math.BigDecimal;

/**
 * The confidence band of a match score under the active threshold profile (the prototype's {@code confidenceBand}):
 * HIGH from {@code max(0.75, accept + 0.10)}, MEDIUM from {@code accept}, else LOW. Viewers without
 * settings.recognition see only the band, never the score.
 */
public record ConfidenceBands(double accept) {

    /** The band, or null without a score. */
    public String band(BigDecimal score) {
        if (score == null) {
            return null;
        }
        double value = score.doubleValue();
        if (value >= Math.max(0.75, accept + 0.10)) {
            return "HIGH";
        }
        return value >= accept ? "MEDIUM" : "LOW";
    }
}
