package com.krztechnologies.attend.camera.service;

import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PermissionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live overlays (Redis contract §2): the worker publishes about 8 frames a second on {@code krz:overlay:<camera>}, and
 * this relays them as SSE {@code overlay} events to the Live View streams of that camera (live.view). A slow client
 * gets the newest frame only, never a queue; nothing is stored. Viewers who lose live.view are dropped at the next
 * heartbeat.
 */
@Service
public class OverlayRelay implements MessageListener, InitializingBean, DisposableBean {

    static final String CHANNEL_PREFIX = "krz:overlay:";

    private static final Logger LOG = LoggerFactory.getLogger(OverlayRelay.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_STREAMS_PER_USER = 8;
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final LiveViewService liveView;
    private final PermissionService permissions;
    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, List<Viewer>> viewers = new ConcurrentHashMap<>();

    public OverlayRelay(LiveViewService liveView, PermissionService permissions, RedisConnectionFactory redis) {
        this.liveView = liveView;
        this.permissions = permissions;
        container.setConnectionFactory(redis);
        container.addMessageListener(this, new PatternTopic(CHANNEL_PREFIX + "*"));
    }

    /** One browser stream of one camera's overlays, holding only the newest frame not yet sent. */
    private final class Viewer {

        private final UUID employeeId;
        private final String camera;
        private final SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        private final AtomicReference<String> latest = new AtomicReference<>();
        private final AtomicBoolean sending = new AtomicBoolean();

        private Viewer(UUID employeeId, String camera) {
            this.employeeId = employeeId;
            this.camera = camera;
        }

        /** Keeps the frame as the newest; starts a sender unless one is running (which then sends it). */
        private void offer(String frame) {
            latest.set(frame);
            if (sending.compareAndSet(false, true)) {
                senders.execute(this::drain);
            }
        }

        private void drain() {
            do {
                String frame;
                while ((frame = latest.getAndSet(null)) != null) {
                    if (!send(SseEmitter.event().name("overlay").data(frame, MediaType.APPLICATION_JSON))) {
                        return;
                    }
                }
                sending.set(false);
            } while (latest.get() != null && sending.compareAndSet(false, true));
        }

        private boolean send(SseEmitter.SseEventBuilder event) {
            synchronized (emitter) {
                try {
                    emitter.send(event);
                    return true;
                } catch (IOException | IllegalStateException e) {
                    LOG.debug("Dropping overlay stream of {}: {}", camera, e.getMessage());
                    remove(this);
                    return false;
                }
            }
        }
    }

    /** A stream of the camera's overlays; 404 for a camera without a stream (unknown or disabled). */
    public SseEmitter subscribe(UUID employeeId, String cameraCode) {
        if (!liveView.streamable(cameraCode)) {
            throw new Errors.NotFound("Camera not found.");
        }
        List<Viewer> own = viewers.values().stream()
                .flatMap(List::stream)
                .filter(v -> v.employeeId.equals(employeeId))
                .toList();
        for (int i = 0; i <= own.size() - MAX_STREAMS_PER_USER; i++) {
            own.get(i).emitter.complete();
            remove(own.get(i));
        }
        Viewer viewer = new Viewer(employeeId, cameraCode);
        viewers.computeIfAbsent(cameraCode, code -> new CopyOnWriteArrayList<>())
                .add(viewer);
        Runnable drop = () -> remove(viewer);
        viewer.emitter.onCompletion(drop);
        // The stream ends cleanly after its timeout (the browser reconnects); left open, Spring raises a timeout error.
        viewer.emitter.onTimeout(viewer.emitter::complete);
        viewer.emitter.onError(e -> drop.run());
        // Flushes the headers at once, so the browser (and nginx) see an open stream before the first frame.
        viewer.send(SseEmitter.event().comment("connected"));
        return viewer.emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        List<Viewer> watching = viewers.get(channel.substring(CHANNEL_PREFIX.length()));
        if (watching == null || watching.isEmpty() || message.getBody().length > MAX_FRAME_BYTES) {
            return;
        }
        String frame = new String(message.getBody(), StandardCharsets.UTF_8);
        watching.forEach(viewer -> viewer.offer(frame));
    }

    /** Every 20 s: a comment keeps proxies from closing idle streams, and viewers without live.view are dropped. */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        viewers.values().stream()
                .flatMap(List::stream)
                .forEach(viewer -> senders.execute(() -> {
                    if (!permissions.has(viewer.employeeId, Permission.LIVE_VIEW)) {
                        viewer.emitter.complete();
                        remove(viewer);
                        return;
                    }
                    viewer.send(SseEmitter.event().comment("hb"));
                }));
    }

    @Override
    public void afterPropertiesSet() {
        container.afterPropertiesSet();
        container.start();
    }

    @Override
    public void destroy() throws Exception {
        container.destroy();
        senders.shutdownNow();
    }

    private void remove(Viewer viewer) {
        List<Viewer> list = viewers.get(viewer.camera);
        if (list != null) {
            list.remove(viewer);
            if (list.isEmpty()) {
                viewers.remove(viewer.camera, list);
            }
        }
    }
}
