package com.krztechnologies.attend.directory.domain;

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

/** SY-n: one run of the Microsoft Graph directory sync. */
@Entity
@Table(name = "directory_sync_run")
public class DirectorySyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, updatable = false)
    private Trigger trigger;

    @Column(name = "triggered_by", updatable = false)
    private UUID triggeredBy;

    @Column(name = "full_sync", nullable = false)
    private boolean fullSync;

    @Column(name = "added", nullable = false)
    private int added;

    @Column(name = "updated", nullable = false)
    private int updated;

    @Column(name = "disabled", nullable = false)
    private int disabled;

    @Column(name = "total", nullable = false)
    private int total;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private Status status;

    @Column(name = "warnings", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String warnings;

    @Column(name = "error")
    private String error;

    @Column(name = "duration_ms")
    private Integer durationMs;

    protected DirectorySyncRun() {}

    public DirectorySyncRun(String code, Instant startedAt, Trigger trigger, UUID triggeredBy) {
        this.code = code;
        this.startedAt = startedAt;
        this.trigger = trigger;
        this.triggeredBy = triggeredBy;
        this.warnings = "[]";
    }

    /**
     * @param warningsJson JSON array of warning texts
     */
    public void finish(
            Instant at,
            boolean full,
            int added,
            int updated,
            int disabled,
            int total,
            String warningsJson,
            Status status,
            String error) {
        this.finishedAt = at;
        this.fullSync = full;
        this.added = added;
        this.updated = updated;
        this.disabled = disabled;
        this.total = total;
        this.warnings = warningsJson;
        this.status = status;
        this.error = error;
        this.durationMs = (int) Math.min(Integer.MAX_VALUE, at.toEpochMilli() - startedAt.toEpochMilli());
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public UUID getTriggeredBy() {
        return triggeredBy;
    }

    public boolean isFullSync() {
        return fullSync;
    }

    public int getAdded() {
        return added;
    }

    public int getUpdated() {
        return updated;
    }

    public int getDisabled() {
        return disabled;
    }

    public int getTotal() {
        return total;
    }

    public Status getStatus() {
        return status;
    }

    public String getWarnings() {
        return warnings;
    }

    public String getError() {
        return error;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    /** Sync run status (vocabulary); null while the run is in progress. */
    public enum Status {
        SUCCESS,
        WARNING,
        FAILED
    }

    /** What started the run. */
    public enum Trigger {
        SCHEDULE,
        MANUAL
    }
}
