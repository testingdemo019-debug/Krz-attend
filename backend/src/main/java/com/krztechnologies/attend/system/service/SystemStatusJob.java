package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.common.realtime.SseHub;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SSE {@code system.status}: every 15 s the status summary is worked out again and sent to every signed-in user when it
 * changed (the topbar pill, its popover and the Dashboard health card). Runs on every instance, for the streams
 * connected to it.
 */
@Component
public class SystemStatusJob {

    private static final Logger LOG = LoggerFactory.getLogger(SystemStatusJob.class);

    private final SystemStatusService status;
    private final SseHub sse;
    private volatile SystemStatusService.Summary last;

    public SystemStatusJob(SystemStatusService status, SseHub sse) {
        this.status = status;
        this.sse = sse;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void publishChanges() {
        try {
            SystemStatusService.Summary now = status.summary();
            if (!Objects.equals(now, last)) {
                last = now;
                sse.publishToAll("system.status", now);
            }
        } catch (RuntimeException e) {
            LOG.warn("System status could not be worked out: {}", e.getMessage());
        }
    }
}
