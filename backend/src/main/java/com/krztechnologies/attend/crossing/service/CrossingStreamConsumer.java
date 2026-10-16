package com.krztechnologies.attend.crossing.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code krz:crossings} as the consumer group {@code backend} (Redis contract §1): {@code XREADGROUP COUNT 50
 * BLOCK 2000}, one database transaction per message, {@code XACK} after the commit. Messages idle for more than 60 s
 * (a crash before the ack) are claimed at startup and every 60 s. A poison message (contract not met) or one that failed
 * on its fifth delivery is copied to {@code krz:crossings:dead}, acknowledged and logged at ERROR with its trace id.
 * The loop runs on its own thread when scheduling is on; the integration tests call {@link #poll} themselves.
 */
@Component
public class CrossingStreamConsumer implements SmartLifecycle {

    public static final String STREAM = "krz:crossings";
    public static final String DEAD_LETTERS = "krz:crossings:dead";
    public static final String GROUP = "backend";

    private static final Logger LOG = LoggerFactory.getLogger(CrossingStreamConsumer.class);
    private static final byte[] STREAM_KEY = STREAM.getBytes(StandardCharsets.UTF_8);
    private static final int BATCH = 50;
    /** Shorter than the Redis command timeout (spring.data.redis.timeout, 2 s), which also bounds a blocking read. */
    private static final Duration BLOCK = Duration.ofSeconds(1);

    private static final Duration CLAIM_IDLE = Duration.ofSeconds(60);
    private static final int MAX_DELIVERIES = 5;

    private final RedisConnectionFactory connections;
    private final StringRedisTemplate redis;
    private final CrossingIngestService ingest;
    private final JsonMapper json;
    private final boolean autoStart;
    private final String consumerName;
    private volatile boolean running;
    private Thread loop;

    public CrossingStreamConsumer(
            RedisConnectionFactory connections,
            StringRedisTemplate redis,
            CrossingIngestService ingest,
            JsonMapper json,
            Environment environment) {
        this.connections = connections;
        this.redis = redis;
        this.ingest = ingest;
        this.json = json;
        this.autoStart = environment.getProperty("krz.scheduling.enabled", Boolean.class, true);
        this.consumerName = hostName();
    }

    /** Creates the consumer group (and the stream) if missing; reads from the start of the stream. */
    public void ensureGroup() {
        try (RedisConnection connection = connections.getConnection()) {
            connection.streamCommands().xGroupCreate(STREAM_KEY, GROUP, ReadOffset.from("0"), true);
            LOG.info("Created the consumer group {} on {}", GROUP, STREAM);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    && (e.getCause() == null
                            || !String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP"))) {
                throw e;
            }
        }
    }

    /**
     * One read: new messages for this consumer, each stored and acknowledged.
     *
     * @param block how long to wait for a message (zero = do not wait)
     * @return how many messages were handled
     */
    public int poll(Duration block) {
        try (RedisConnection connection = connections.getConnection()) {
            return poll(connection, block);
        }
    }

    /** A blocking read holds its own Redis connection, so the loop keeps one for its lifetime. */
    private int poll(RedisConnection connection, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().count(BATCH);
        if (!block.isZero()) {
            options = options.block(block);
        }
        List<ByteRecord> records = connection
                .streamCommands()
                .xReadGroup(
                        Consumer.from(GROUP, consumerName),
                        options,
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        if (records == null) {
            return 0;
        }
        for (ByteRecord record : records) {
            handle(record.getId(), strings(record.getValue()), 1);
        }
        return records.size();
    }

    /**
     * Claims messages another consumer (or this one before a crash) left unacknowledged for longer than {@code
     * minIdle} and handles them again.
     *
     * @return how many messages were handled
     */
    public int reclaim(Duration minIdle) {
        var pending = redis.opsForStream().pending(STREAM, GROUP, Range.unbounded(), BATCH);
        List<RecordId> ids = new ArrayList<>();
        Map<RecordId, Long> deliveries = new LinkedHashMap<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                ids.add(message.getId());
                deliveries.put(message.getId(), message.getTotalDeliveryCount() + 1);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        List<MapRecord<String, Object, Object>> claimed =
                redis.opsForStream().claim(STREAM, GROUP, consumerName, minIdle, ids.toArray(RecordId[]::new));
        for (MapRecord<String, Object, Object> record : claimed) {
            Map<String, String> fields = new LinkedHashMap<>();
            record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
            handle(record.getId(), fields, deliveries.getOrDefault(record.getId(), (long) MAX_DELIVERIES));
        }
        return claimed.size();
    }

    /** How many messages are in the dead-letter stream (Settings → System). */
    public long deadLetterCount() {
        Long size = redis.opsForStream().size(DEAD_LETTERS);
        return size == null ? 0 : size;
    }

    private void handle(RecordId id, Map<String, String> fields, long delivery) {
        String trace = fields.getOrDefault("trace_id", "");
        try {
            ingest.ingest(CrossingMessage.parse(fields, json));
            acknowledge(id);
        } catch (CrossingMessage.Invalid e) {
            deadLetter(id, fields, e.getMessage(), trace);
        } catch (RuntimeException e) {
            if (delivery >= MAX_DELIVERIES) {
                deadLetter(id, fields, e.getClass().getSimpleName() + ": " + e.getMessage(), trace);
            } else {
                LOG.warn("Crossing message {} failed (delivery {}), retried later (trace {})", id, delivery, trace, e);
            }
        }
    }

    private void deadLetter(RecordId id, Map<String, String> fields, String error, String trace) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put("dead_reason", error);
        copy.put("original_id", id.getValue());
        redis.opsForStream().add(StreamRecords.newRecord().in(DEAD_LETTERS).ofMap(copy));
        acknowledge(id);
        LOG.error(
                "Crossing message {} could not be processed and was moved to {}: {} (trace {})",
                id,
                DEAD_LETTERS,
                error,
                trace);
    }

    private void acknowledge(RecordId id) {
        redis.opsForStream().acknowledge(STREAM, GROUP, id);
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = Thread.ofPlatform().name("crossing-stream").daemon().start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
            try {
                loop.join(BLOCK.multipliedBy(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        long lastClaim = 0;
        boolean ready = false;
        RedisConnection connection = null;
        while (running) {
            try {
                if (!ready) {
                    ensureGroup();
                    ready = true;
                }
                long nowMillis = System.nanoTime() / 1_000_000;
                if (lastClaim == 0 || nowMillis - lastClaim >= CLAIM_IDLE.toMillis()) {
                    reclaim(CLAIM_IDLE);
                    lastClaim = nowMillis;
                }
                if (connection == null) {
                    connection = connections.getConnection();
                }
                poll(connection, BLOCK);
            } catch (RuntimeException e) {
                closeQuietly(connection);
                connection = null;
                if (!running) {
                    return;
                }
                LOG.warn("Reading {} failed; retrying in {} s: {}", STREAM, BLOCK.toSeconds(), e.getMessage());
                try {
                    Thread.sleep(BLOCK.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(RedisConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                LOG.debug("Closing the stream connection failed: {}", e.getMessage());
            }
        }
    }

    private static Map<String, String> strings(Map<byte[], byte[]> raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        raw.forEach((k, v) -> fields.put(
                new String(k, StandardCharsets.UTF_8), v == null ? "" : new String(v, StandardCharsets.UTF_8)));
        return fields;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "backend";
        }
    }
}
