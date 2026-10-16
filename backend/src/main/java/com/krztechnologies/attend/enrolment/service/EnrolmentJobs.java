package com.krztechnologies.attend.enrolment.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Enrolment sessions left open past their time expire every five minutes; their unsaved captures go. */
@Component
public class EnrolmentJobs {

    private final EnrolmentService enrolment;

    public EnrolmentJobs(EnrolmentService enrolment) {
        this.enrolment = enrolment;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    @SchedulerLock(name = "enrolment-expiry", lockAtMostFor = "PT10M")
    public void expireSessions() {
        enrolment.expire();
    }
}
