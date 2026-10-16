package com.krztechnologies.attend.common.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Settings → Attendance rules (app_setting key {@code attendanceRules}), the inputs of the punch rules (R2, R6, R8).
 *
 * @param duplicateMinutes same-direction crossings within this many minutes are duplicates
 * @param turnedBackSeconds an IN and an OUT this close together cancel each other
 * @param absentCutoffHours hours after shift start when a day without check-in counts as absent (R2 {@code cutoff})
 * @param workedHours {@code SPAN} (first IN to last OUT) or {@code INSIDE} (sum of IN–OUT pairs)
 * @param pushMode {@code FINAL} (first IN and last OUT) or {@code EVERY} (every counted crossing) to HRMS
 */
public record AttendanceRulesSettings(
        @Min(0) @Max(30) int duplicateMinutes,
        @Min(0) @Max(600) int turnedBackSeconds,
        @Min(1) @Max(12) int absentCutoffHours,
        @NotNull WorkedHours workedHours,
        @NotNull PushMode pushMode) {

    /** How worked time is counted. */
    public enum WorkedHours {
        SPAN,
        INSIDE
    }

    /** Which punches go to HRMS. */
    public enum PushMode {
        FINAL,
        EVERY
    }
}
