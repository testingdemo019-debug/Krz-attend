package com.krztechnologies.attend.camera.domain;

/**
 * Camera status (vocabulary): derived from the worker's camera heartbeat ({@code live} → LIVE, {@code connecting} →
 * CONNECTING, {@code offline} or no heartbeat → OFFLINE); DISABLED while processing is turned off.
 */
public enum CameraStatus {
    LIVE("Live"),
    CONNECTING("Connecting"),
    OFFLINE("Offline"),
    DISABLED("Disabled");

    private final String label;

    CameraStatus(String label) {
        this.label = label;
    }

    /** The label used in audit texts ("Camera went offline": Live → Offline). */
    public String label() {
        return label;
    }
}
