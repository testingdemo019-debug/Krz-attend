package com.krztechnologies.attend.camera.domain;

import java.time.Instant;

/**
 * The worker's camera heartbeat ({@code krz:hb:camera:<code>}, TTL 10 s, Redis contract §4).
 *
 * @param lastFrame when the worker last decoded a frame, null when not reported
 * @param clockOffsetSeconds the camera clock's offset from the host (ONVIF), null when unknown
 * @param errorCode the worker's error code while connecting or offline
 */
public record CameraHeartbeat(
        CameraStatus status,
        Double fps,
        Integer latencyMs,
        String resolution,
        String codec,
        Instant lastFrame,
        Double clockOffsetSeconds,
        String errorCode) {}
