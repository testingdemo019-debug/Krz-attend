package com.krztechnologies.attend.rules.service;

/** The status of a KRZ HRMS outbox row (R8), with the spec's label. */
public enum OutboxStatus {
    PENDING("Pending"),
    SENT("Sent"),
    FAILED("Failed"),
    VOIDED("Voided"),
    BLOCKED_NO_EMPLOYEE_ID("Blocked – no Employee ID"),
    HELD_NOT_CONNECTED("Held – HRMS not connected");

    private final String label;

    OutboxStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
