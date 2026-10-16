package com.krztechnologies.attend.directory.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.directory.domain.DirectorySyncRun;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks every 10 minutes whether the directory sync is due (Settings → Microsoft sign-in → every N hours). */
@Component
public class DirectorySyncJob {

    private final DirectorySyncService sync;

    public DirectorySyncJob(DirectorySyncService sync) {
        this.sync = sync;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    @SchedulerLock(name = "directory-sync", lockAtMostFor = "PT30M")
    public void runIfDue() {
        if (sync.isDue()) {
            sync.run(DirectorySyncRun.Trigger.SCHEDULE, AuditActor.DIRECTORY_SYNC);
        }
    }
}
