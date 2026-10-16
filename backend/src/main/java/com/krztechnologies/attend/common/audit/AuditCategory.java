package com.krztechnologies.attend.common.audit;

/** Audit categories (stored UPPER_SNAKE; the frontend vocabulary maps them to "Sign-in", "Users & roles", …). */
public enum AuditCategory {
    SIGN_IN,
    USERS_ROLES,
    ORGANISATION,
    EMPLOYEES,
    ENROLMENT,
    REVIEW,
    ATTENDANCE,
    CAMERAS,
    RECOGNITION,
    SETTINGS,
    HRMS,
    PRIVACY,
    EXPORT,
    LIVE_VIEW,
    SYSTEM
}
