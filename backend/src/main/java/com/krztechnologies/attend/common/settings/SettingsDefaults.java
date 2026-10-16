package com.krztechnologies.attend.common.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Defaults of every settings area, from application.yml ({@code krz.settings.*}, partly bound to deploy/.env). The
 * app_setting rows override them field by field.
 */
@Validated
@ConfigurationProperties("krz.settings")
public record SettingsDefaults(
        @Valid @NotNull GeneralSettings general,
        @Valid @NotNull SignInSettings signin,
        @Valid @NotNull AttendanceRulesSettings attendanceRules,
        @Valid @NotNull EnrolmentSettings enrolment,
        @Valid @NotNull PrivacySettings privacy,
        @Valid @NotNull HrmsSettings hrms) {}
