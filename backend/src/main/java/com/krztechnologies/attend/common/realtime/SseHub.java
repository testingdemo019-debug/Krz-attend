package com.krztechnologies.attend.common.realtime;

import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PermissionService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events to signed-in browsers: at most 5 streams per user, 30 min timeout (the browser reconnects), an
 * opening comment and a heartbeat comment every 20 s. Each event is filtered by permission. Sends happen on virtual threads, one stream at a
 * time per emitter, so a slow client never blocks the publisher; a failed stream is dropped.
 */
@Component
public class SseHub {

    private static final Logger LOG = LoggerFactory.getLogger(SseHub.class);
    private static final int MAX_STREAMS_PER_USER = 5;
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final PermissionService permissions;
    private final Map<UUID, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();

    public SseHub(PermissionService permissions) {
        this.permissions = permissions;
    }

    public SseEmitter subscribe(UUID employeeId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<SseEmitter> own = streams.computeIfAbsent(employeeId, id -> new CopyOnWriteArrayList<>());
        while (own.size() >= MAX_STREAMS_PER_USER) {
            own.removeFirst().complete();
        }
        own.add(emitter);
        Runnable remove = () -> drop(employeeId, emitter);
        emitter.onCompletion(remove);
        // The stream ends cleanly after its timeout (the browser reconnects); left open, Spring raises a timeout error.
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> remove.run());
        try {
            // Flushes the headers at once, so the browser (and nginx) see an open stream before the first event.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** To every connected user who holds {@code required}. */
    public void publish(String eventType, Object payload, Permission required) {
        streams.forEach((employeeId, list) -> {
            if (permissions.has(employeeId, required)) {
                list.forEach(emitter -> send(employeeId, emitter, eventType, payload));
            }
        });
    }

    /** To every connected user (events every signed-in user may see, such as the system status pill). */
    public void publishToAll(String eventType, Object payload) {
        streams.forEach((employeeId, list) -> list.forEach(emitter -> send(employeeId, emitter, eventType, payload)));
    }

    /** To one user (their own notifications). */
    public void publishToUser(UUID employeeId, String eventType, Object payload) {
        streams.getOrDefault(employeeId, List.of()).forEach(emitter -> send(employeeId, emitter, eventType, payload));
    }

    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        streams.forEach((employeeId, list) -> list.forEach(emitter -> senders.execute(() -> {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    drop(employeeId, emitter);
                }
            }
        })));
    }

    public int connectedStreams() {
        return streams.values().stream().mapToInt(List::size).sum();
    }

    private void send(UUID employeeId, SseEmitter emitter, String eventType, Object payload) {
        senders.execute(() -> {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name(eventType).data(payload, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    LOG.debug("Dropping SSE stream of employeeId={}: {}", employeeId, e.getMessage());
                    drop(employeeId, emitter);
                }
            }
        });
    }

    private void drop(UUID employeeId, SseEmitter emitter) {
        List<SseEmitter> list = streams.get(employeeId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                streams.remove(employeeId, list);
            }
        }
    }
}
