package com.krztechnologies.attend.common.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Settings → Microsoft sign-in, editable part (app_setting key {@code signin}). The connection facts (tenant, client,
 * redirect URI, secret expiry) are read-only and come from the environment.
 *
 * @param idleTimeoutMin sign out after this much user inactivity
 * @param syncEvery directory sync interval in hours
 * @param eraseAfterDays biometric data of a disabled account is erased after this many days
 */
public record SignInSettings(
        @Min(5) @Max(480) int idleTimeoutMin,
        @Min(1) @Max(24) int syncEvery,
        @Min(1) @Max(365) int eraseAfterDays) {}
