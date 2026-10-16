package com.krztechnologies.attend.rules.service;

/** How a crossing's identity was decided (stored in {@code crossing.decision}). */
public enum Decision {
    AUTO,
    PENDING_REVIEW,
    RESOLVED_IN_REVIEW,
    NOT_AN_EMPLOYEE,
    REJECTED,
    SPOOF_CONFIRMED,
    EXPIRED_NO_ACTION
}
