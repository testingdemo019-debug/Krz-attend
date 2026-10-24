package com.krztechnologies.attend.camera.domain;

/**
 * How a camera decides IN and OUT (D-120); the label is the one users see. Each method has its own drawing on the
 * camera picture and its own settings: a camera keeps both drawings, so switching back restores the other one.
 */
public enum CountingMethod {
    /** A crossing is the feet passing a line on the floor, confirmed over a few frames, with a U-turn window. */
    LINE_CROSSING("Line crossing"),
    /**
     * For compact entrances where the floor is out of view: a walk counts by where it starts and ends, the door area
     * or the room. No line, confirmation frames or U-turn window.
     */
    DOORWAY_ZONE("Doorway zone");

    private final String label;

    CountingMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
