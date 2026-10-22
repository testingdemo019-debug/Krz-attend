package com.krztechnologies.attend.employee.domain;

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

/** One line of a face profile's history (insert-only). */
@Entity
@Table(name = "face_profile_history")
public class FaceProfileHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private Action action;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_label", nullable = false, updatable = false)
    private String actorLabel;

    @Column(name = "detail", updatable = false)
    private String detail;

    protected FaceProfileHistory() {}

    public FaceProfileHistory(
            UUID employeeId, Instant occurredAt, Action action, UUID actorId, String actorLabel, String detail) {
        this.employeeId = employeeId;
        this.occurredAt = occurredAt;
        this.action = action;
        this.actorId = actorId;
        this.actorLabel = actorLabel;
        this.detail = detail;
    }

    /** History actions (the frontend vocabulary maps them to the prototype's labels). */
    public enum Action {
        ENROLLED,
        REENROLLED,
        FLAGGED_REENROL,
        DEACTIVATED,
        REACTIVATED,
        ERASED,
        TEMPLATE_ADDED_FROM_REVIEW,
        TEMPLATE_ADDED_FROM_CROSSING,
        ENROLMENT_DECLINED,
        CONSENT_WITHDRAWN
    }
}
