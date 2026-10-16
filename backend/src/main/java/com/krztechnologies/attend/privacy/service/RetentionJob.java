package com.krztechnologies.attend.privacy.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The nightly retention run at 02:30 in the organisation's time zone (APP_TIMEZONE, IST), one instance at a time. */
@Component
public class RetentionJob {

    private final RetentionService retention;

    public RetentionJob(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "${krz.app.timezone}")
    @SchedulerLock(name = "retention", lockAtMostFor = "PT3H")
    public void runNightly() {
        retention.run();
    }
}
