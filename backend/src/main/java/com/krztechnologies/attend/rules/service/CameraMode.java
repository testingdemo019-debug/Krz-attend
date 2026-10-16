package com.krztechnologies.attend.rules.service;

/** A camera's direction mode: which crossings may become punches. The label is the one users see. */
public enum CameraMode {
    BOTH("IN and OUT by crossing direction", "IN + OUT"),
    IN("IN only", "IN only"),
    OUT("OUT only", "OUT only"),
    MONITOR("Monitor only (no punches)", "Monitor");

    private final String label;
    private final String shortLabel;

    CameraMode(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String label() {
        return label;
    }

    /** The prototype's short label (camera list, audit texts). */
    public String shortLabel() {
        return shortLabel;
    }

    /** Whether a crossing in this direction may count on a camera in this mode. */
    public boolean allows(Direction direction) {
        return switch (this) {
            case BOTH -> true;
            case IN -> direction == Direction.IN;
            case OUT -> direction == Direction.OUT;
            case MONITOR -> false;
        };
    }
}
