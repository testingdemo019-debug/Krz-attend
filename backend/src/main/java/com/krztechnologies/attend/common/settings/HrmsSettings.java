package com.krztechnologies.attend.common.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Settings → KRZ HRMS (app_setting key {@code hrms}): retry policy of the punch outbox (R8).
 *
 * @param backoffSeconds wait before attempt n+1; the last value repeats
 */
public record HrmsSettings(
        @Min(1) @Max(20) int maxAttempts, @NotEmpty List<@Min(1) @Max(3600) Integer> backoffSeconds) {

    public HrmsSettings {
        backoffSeconds = List.copyOf(backoffSeconds);
    }
}
