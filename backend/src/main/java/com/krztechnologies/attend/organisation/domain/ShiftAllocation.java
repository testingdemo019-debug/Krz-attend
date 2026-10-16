package com.krztechnologies.attend.organisation.domain;

import com.krztechnologies.attend.rules.service.ShiftCalendar;
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
import java.util.UUID;

/**
 * ALC-nnnn: a shift for an employee or a department from a day, optionally until a day (R9). Ending sets
 * {@code validUntil}; cancelling (only before it starts) keeps the row for the history.
 */
@Entity
@Table(name = "shift_allocation")
public class ShiftAllocation {

    /** The prototype's {@code allocationState}. */
    public enum State {
        ACTIVE,
        SCHEDULED,
        ENDED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "shift_id", nullable = false, updatable = false)
    private Long shiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private ShiftCalendar.Target targetType;

    @Column(name = "employee_id", updatable = false)
    private UUID employeeId;

    @Column(name = "department_id", updatable = false)
    private Long departmentId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_label", updatable = false)
    private String createdByLabel;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected ShiftAllocation() {}

    public ShiftAllocation(
            String code,
            Long shiftId,
            ShiftCalendar.Target targetType,
            UUID employeeId,
            Long departmentId,
            LocalDate validFrom,
            LocalDate validUntil,
            String note,
            UUID createdBy,
            String createdByLabel) {
        this.code = code;
        this.shiftId = shiftId;
        this.targetType = targetType;
        this.employeeId = employeeId;
        this.departmentId = departmentId;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.note = note;
        this.createdBy = createdBy;
        this.createdByLabel = createdByLabel;
    }

    public void endOn(LocalDate until) {
        this.validUntil = until;
    }

    public void cancel(UUID by, Instant at) {
        this.cancelledBy = by;
        this.cancelledAt = at;
    }

    public State state(LocalDate today) {
        if (cancelledAt != null) {
            return State.CANCELLED;
        }
        if (validUntil != null && validUntil.isBefore(today)) {
            return State.ENDED;
        }
        return validFrom.isAfter(today) ? State.SCHEDULED : State.ACTIVE;
    }

    /** As the calendar sees it. */
    public ShiftCalendar.Allocation toCalendar(String shiftCode) {
        return new ShiftCalendar.Allocation(
                code, shiftCode, targetType, employeeId, departmentId, validFrom, validUntil, cancelledAt != null);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public ShiftCalendar.Target getTargetType() {
        return targetType;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    public String getNote() {
        return note;
    }

    public String getCreatedByLabel() {
        return createdByLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
