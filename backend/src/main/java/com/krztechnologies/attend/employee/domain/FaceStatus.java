package com.krztechnologies.attend.employee.domain;

/** Face status (vocabulary "People"). */
public enum FaceStatus {
    ENROLLED("Enrolled"),
    NOT_ENROLLED("Not enrolled"),
    NEEDS_REENROL("Needs re-enrolment"),
    CONSENT_PENDING("Consent pending"),
    DEACTIVATED("Deactivated"),
    ERASED("Erased");

    private final String label;

    FaceStatus(String label) {
        this.label = label;
    }

    /** The prototype's {@code FACE_STATUS} label (audit before/after values). */
    public String label() {
        return label;
    }

    /** Profiles that can be matched by the worker (and therefore have something to deactivate). */
    public boolean isActive() {
        return this == ENROLLED || this == NEEDS_REENROL;
    }
}
