package com.krztechnologies.attend.common.settings;

/**
 * A settings area: its app_setting key, its typed record and the label used in audit entries.
 *
 * @param <T> the settings record
 */
public record SettingsArea<T>(String key, Class<T> type, String label) {

    public static final SettingsArea<GeneralSettings> GENERAL =
            new SettingsArea<>("general", GeneralSettings.class, "General");
    public static final SettingsArea<SignInSettings> SIGN_IN =
            new SettingsArea<>("signin", SignInSettings.class, "Microsoft sign-in");
    public static final SettingsArea<AttendanceRulesSettings> ATTENDANCE_RULES =
            new SettingsArea<>("attendanceRules", AttendanceRulesSettings.class, "Attendance rules");
    public static final SettingsArea<EnrolmentSettings> ENROLMENT =
            new SettingsArea<>("enrolment", EnrolmentSettings.class, "Enrolment & quality");
    public static final SettingsArea<PrivacySettings> PRIVACY =
            new SettingsArea<>("privacy", PrivacySettings.class, "Privacy & retention");
    public static final SettingsArea<HrmsSettings> HRMS = new SettingsArea<>("hrms", HrmsSettings.class, "KRZ HRMS");
}
