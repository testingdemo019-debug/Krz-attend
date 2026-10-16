package com.krztechnologies.attend.common.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Settings → Privacy &amp; retention (app_setting key {@code privacy}), R17.
 *
 * @param cropDays entrance crops are deleted after this many days
 * @param unknownHours unresolved unknown-face items expire after this many hours
 * @param eventDays crossings and everything that references them are deleted after this many days
 * @param auditDays audit entries are purged after this many days (never below the database's 30-day floor)
 */
public record PrivacySettings(
        @Min(1) @Max(365) int cropDays,
        @Min(1) @Max(720) int unknownHours,
        @Min(30) @Max(3650) int eventDays,
        @Min(30) @Max(3650) int auditDays) {}
