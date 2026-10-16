package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The attendance schedules: R7 time-driven refresh every minute, R13 day close every five minutes. */
@Component
public class AttendanceJobs {

    private final AttendanceRecomputeService attendance;
    private final DayCloseService dayClose;

    public AttendanceJobs(AttendanceRecomputeService attendance, DayCloseService dayClose) {
        this.attendance = attendance;
        this.dayClose = dayClose;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "attendance-refresh", lockAtMostFor = "PT5M")
    public void refresh() {
        attendance.refreshTimeDriven();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    @SchedulerLock(name = "day-close", lockAtMostFor = "PT30M")
    public void dayClose() {
        dayClose.closeDue(DayCloseService.Trigger.SCHEDULE, AuditActor.SYSTEM);
    }
}
