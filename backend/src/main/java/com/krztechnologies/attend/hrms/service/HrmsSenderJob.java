package com.krztechnologies.attend.hrms.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 5 s, the due outbox entries go to KRZ HRMS. Scheduled only while HRMS is enabled (HRMS_ENABLED=true). */
@Component
@ConditionalOnProperty(name = "krz.hrms.enabled", havingValue = "true")
public class HrmsSenderJob {

    private final HrmsSender sender;

    public HrmsSenderJob(HrmsSender sender) {
        this.sender = sender;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 30_000)
    @SchedulerLock(name = "hrms-sender", lockAtMostFor = "PT10M")
    public void send() {
        sender.sendDue();
    }
}
