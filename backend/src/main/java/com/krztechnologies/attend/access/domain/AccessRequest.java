package com.krztechnologies.attend.access.domain;

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
import java.util.UUID;

/** AR-nnnn: a signed-in user without a role asks for access (NR-06). */
@Entity
@Table(name = "access_request")
public class AccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "note", updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected AccessRequest() {}

    public AccessRequest(String code, UUID employeeId, String note, Instant createdAt) {
        this.code = code;
        this.employeeId = employeeId;
        this.note = note;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
    }

    public void approve(UUID by, Instant at) {
        decide(Status.APPROVED, by, at, null);
    }

    public void decline(UUID by, Instant at, String reason) {
        decide(Status.DECLINED, by, at, reason);
    }

    private void decide(Status outcome, UUID by, Instant at, String reason) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Access request " + code + " is already " + status);
        }
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = at;
        this.decisionReason = reason;
    }

    public String getCode() {
        return code;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public String getNote() {
        return note;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Access request status (vocabulary). */
    public enum Status {
        PENDING,
        APPROVED,
        DECLINED
    }
}
