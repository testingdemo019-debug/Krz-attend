package com.krztechnologies.attend.camera.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reads the heartbeats every 5 s (heartbeat TTL 10 s) and recomputes the daily camera health every minute. */
@Component
public class CameraHealthJob {

    private final CameraHealthService health;

    public CameraHealthJob(CameraHealthService health) {
        this.health = health;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    @SchedulerLock(name = "camera-health", lockAtMostFor = "PT1M")
    public void check() {
        health.check();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "camera-health-rollup", lockAtMostFor = "PT5M")
    public void rollUp() {
        health.rollUp();
    }
}
