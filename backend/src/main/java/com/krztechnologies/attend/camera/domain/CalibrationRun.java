package com.krztechnologies.attend.camera.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A calibration "test walk": while it runs the worker reports the camera's crossings to the backend instead of the
 * stream, so no punch is created. The result (crossings and verdict) is stored once the worker reports the end.
 */
@Entity
@Table(name = "calibration_run")
public class CalibrationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_id", nullable = false, updatable = false)
    private Long cameraId;

    @Column(name = "worker_run_id", updatable = false)
    private String workerRunId;

    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "result", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String result;

    protected CalibrationRun() {}

    public CalibrationRun(Long cameraId, String workerRunId, UUID startedBy, Instant startedAt) {
        this.cameraId = cameraId;
        this.workerRunId = workerRunId;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    /** @param resultJson {crossings:[…], verdict} */
    public void finish(Status outcome, Instant at, String resultJson) {
        this.status = outcome;
        this.finishedAt = at;
        this.result = resultJson;
    }

    public Long getId() {
        return id;
    }

    public Long getCameraId() {
        return cameraId;
    }

    public String getWorkerRunId() {
        return workerRunId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    /** calibration_run.status values. */
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }
}
