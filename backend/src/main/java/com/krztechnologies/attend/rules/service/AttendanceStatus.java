package com.krztechnologies.attend.rules.service;

/**
 * The status of an employee's shift day, with the one- or two-letter code of the attendance log grid and the
 * prototype's label ({@code ATTENDANCE_STATUS}).
 */
public enum AttendanceStatus {
    INSIDE("P", "Inside"),
    STEPPED_OUT("P", "Stepped out"),
    CHECKED_OUT("P", "Checked out"),
    LATE("L", "Late"),
    EARLY_EXIT("EE", "Early exit"),
    HALF_DAY("HD", "Half day"),
    MISSING_CHECK_OUT("MO", "Missing check-out"),
    MISSING_CHECK_IN("MI", "Missing check-in"),
    NOT_YET_IN("·", "Not yet in"),
    ABSENT("A", "Absent"),
    HOLIDAY("H", "Holiday"),
    WEEKLY_OFF("WO", "Weekly off"),
    WORKED_ON_HOLIDAY("WH", "Worked on holiday"),
    WORKED_ON_WEEKLY_OFF("WW", "Worked on weekly off"),
    NOT_TRACKED("NT", "Not tracked");

    private final String code;
    private final String label;

    AttendanceStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
