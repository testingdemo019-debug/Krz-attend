package com.krztechnologies.attend.recognition.service;

import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.worker.WorkerClient;
import com.krztechnologies.attend.common.worker.WorkerException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** The worker's models and version from its {@code /health}, cached for 60 s (also when the worker is down). */
@Service
public class ModelInfoService {

    private static final Duration CACHE_FOR = Duration.ofSeconds(60);

    private final WorkerClient worker;
    private final TimeService time;
    private volatile Cached cached;

    public ModelInfoService(WorkerClient worker, TimeService time) {
        this.worker = worker;
        this.time = time;
    }

    private record Cached(RecognitionViews.ModelInfo info, Instant at) {}

    public RecognitionViews.ModelInfo current() {
        Instant now = time.now();
        Cached hit = cached;
        if (hit != null && now.isBefore(hit.at().plus(CACHE_FOR))) {
            return hit.info();
        }
        RecognitionViews.ModelInfo info;
        try {
            WorkerClient.Health health = worker.health();
            info = new RecognitionViews.ModelInfo(
                    true, health.status(), health.version(), health.provider(), health.models(), null);
        } catch (WorkerException e) {
            info = new RecognitionViews.ModelInfo(false, null, null, null, List.of(), e.code());
        }
        cached = new Cached(info, now);
        return info;
    }
}
