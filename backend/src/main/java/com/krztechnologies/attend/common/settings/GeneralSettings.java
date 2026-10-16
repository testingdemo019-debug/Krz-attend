package com.krztechnologies.attend.common.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;

/**
 * Settings → General (app_setting key {@code general}).
 *
 * @param timeFormat {@code 12h} or {@code 24h}
 * @param employeeIdPattern regular expression every HRMS Employee ID must match
 * @param undoWindowSeconds how long undo tokens (review decisions, corrections) stay valid
 */
public record GeneralSettings(
        @NotBlank @Size(max = 120) String orgName,
        @NotNull ZoneId timezone,
        @NotBlank @Size(max = 20) String dateFormat,
        @NotBlank @Pattern(regexp = "12h|24h") String timeFormat,
        @NotBlank @Size(max = 100) String employeeIdPattern,
        @Min(3) @Max(60) int undoWindowSeconds) {}
