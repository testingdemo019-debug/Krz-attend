package com.krztechnologies.attend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** PR-nnn: an employee's request about their data (access, correction, erasure), due 30 days after receipt. */
@Entity
@Table(name = "privacy_request")
public class PrivacyRequest {

    /** The legal reply period (DPDP): the request is due this many days after it was received. */
    public static final int DUE_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "due_on", nullable = false, updatable = false)
    private LocalDate dueOn;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected PrivacyRequest() {}

    public PrivacyRequest(
            String code, UUID employeeId, Type type, LocalDate receivedOn, String note, Instant createdAt) {
        this.code = code;
        this.employeeId = employeeId;
        this.type = type;
        this.status = Status.OPEN;
        this.receivedOn = receivedOn;
        this.dueOn = receivedOn.plusDays(DUE_DAYS);
        this.note = note;
        this.createdAt = createdAt;
    }

    /** Whether the request may move from its current status to {@code next}. */
    public boolean canMoveTo(Status next) {
        return status.next().contains(next);
    }

    /** Moves the request on; COMPLETED and DECLINED are final and record when. */
    public void moveTo(Status next, String outcome, UUID by, Instant at) {
        this.status = next;
        this.handledBy = by;
        if (outcome != null) {
            this.outcome = outcome;
        }
        if (next.isFinal()) {
            this.completedAt = at;
        }
    }

    public String getCode() {
        return code;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public Type getType() {
        return type;
    }

    public Status getStatus() {
        return status;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public String getNote() {
        return note;
    }

    public UUID getHandledBy() {
        return handledBy;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public Integer getVersion() {
        return version;
    }

    /** What the employee asked for (vocabulary "Privacy request type"). */
    public enum Type {
        ACCESS("Access request"),
        CORRECTION("Correction"),
        ERASURE("Erasure");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Open → In progress → Completed, or Declined from either open state (vocabulary "Privacy request status"). */
    public enum Status {
        OPEN("Open"),
        IN_PROGRESS("In progress"),
        COMPLETED("Completed"),
        DECLINED("Declined");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isFinal() {
            return this == COMPLETED || this == DECLINED;
        }

        Set<Status> next() {
            return switch (this) {
                case OPEN -> Set.of(IN_PROGRESS, DECLINED);
                case IN_PROGRESS -> Set.of(COMPLETED, DECLINED);
                case COMPLETED, DECLINED -> Set.of();
            };
        }
    }
}
