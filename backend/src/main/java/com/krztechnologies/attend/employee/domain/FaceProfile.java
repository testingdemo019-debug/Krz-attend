package com.krztechnologies.attend.employee.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Face status of an employee (one row per employee once enrolment or consent has started). */
@Entity
@Table(name = "face_profile")
public class FaceProfile {

    @Id
    @Column(name = "employee_id")
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FaceStatus status;

    @Column(name = "reason")
    private String reason;

    @Column(name = "scheduled_erasure_on")
    private LocalDate scheduledErasureOn;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected FaceProfile() {}

    /** A profile for someone who has none yet (not enrolled). */
    public FaceProfile(UUID employeeId) {
        this.employeeId = employeeId;
        this.status = FaceStatus.NOT_ENROLLED;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public FaceStatus getStatus() {
        return status;
    }

    public LocalDate getScheduledErasureOn() {
        return scheduledErasureOn;
    }

    public String getReason() {
        return reason;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    /** R15 save: enrolled now; a pending scheduled erasure and an old reason go. */
    public void enrol(Instant at) {
        this.status = FaceStatus.ENROLLED;
        this.reason = null;
        this.scheduledErasureOn = null;
        this.enrolledAt = at;
    }

    /** The employee declined enrolment: consent pending becomes not enrolled. */
    public void declined() {
        if (status == FaceStatus.CONSENT_PENDING) {
            this.status = FaceStatus.NOT_ENROLLED;
        }
    }

    /** Stop matching this person (templates are kept, so the profile can be reactivated). */
    public void deactivate(String why) {
        this.status = FaceStatus.DEACTIVATED;
        this.reason = why;
    }

    /** Match again with the kept templates; a pending scheduled erasure is cancelled. */
    public void reactivate() {
        this.status = FaceStatus.ENROLLED;
        this.reason = null;
        this.scheduledErasureOn = null;
    }

    /** The Microsoft account was disabled: stop matching at once and schedule the erasure. */
    public void deactivateForDisabledAccount(String reason, LocalDate eraseOn) {
        this.status = FaceStatus.DEACTIVATED;
        this.reason = reason;
        this.scheduledErasureOn = eraseOn;
    }

    /** The account was re-enabled before the erasure ran; the profile stays deactivated until reactivated. */
    public void cancelScheduledErasure() {
        this.scheduledErasureOn = null;
    }
}
