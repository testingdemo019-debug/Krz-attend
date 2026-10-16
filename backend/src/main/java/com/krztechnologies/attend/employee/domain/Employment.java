package com.krztechnologies.attend.employee.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An employee's employment details, kept in KRZ Attend until the HRMS master-data sync exists (P-08): HRMS Employee ID,
 * department, designation, type, location, manager, dates and attendance tracking (R5). Directory sync creates
 * employees without this row ("Employment details missing").
 */
@Entity
@Table(name = "employment")
public class Employment {

    @Id
    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "hrms_employee_id")
    private String hrmsEmployeeId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "designation")
    private String designation;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type")
    private EmploymentType employmentType;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "join_date")
    private LocalDate joinDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_mode", nullable = false)
    private TrackingMode trackingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_reason")
    private TrackingReason trackingReason;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Employment() {}

    /** A new record: not tracked because there is no face profile yet (the database default). */
    public Employment(UUID employeeId) {
        this.employeeId = employeeId;
        this.trackingMode = TrackingMode.NOT_TRACKED;
        this.trackingReason = TrackingReason.NO_FACE;
    }

    public void setHrmsEmployeeId(String hrmsEmployeeId) {
        this.hrmsEmployeeId = hrmsEmployeeId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public void setEmploymentType(EmploymentType employmentType) {
        this.employmentType = employmentType;
    }

    public void setLocationId(Long locationId) {
        this.locationId = locationId;
    }

    public void setManagerId(UUID managerId) {
        this.managerId = managerId;
    }

    public void setDates(LocalDate joinDate, LocalDate endDate) {
        this.joinDate = joinDate;
        this.endDate = endDate;
    }

    /** Tracked by camera (no reason), or not tracked with a reason (the database requires exactly that). */
    public void setTracking(TrackingMode mode, TrackingReason reason) {
        this.trackingMode = mode;
        this.trackingReason = mode == TrackingMode.TRACKED ? null : reason;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public String getHrmsEmployeeId() {
        return hrmsEmployeeId;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public String getDesignation() {
        return designation;
    }

    public EmploymentType getEmploymentType() {
        return employmentType;
    }

    public Long getLocationId() {
        return locationId;
    }

    public UUID getManagerId() {
        return managerId;
    }

    public LocalDate getJoinDate() {
        return joinDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public TrackingMode getTrackingMode() {
        return trackingMode;
    }

    public TrackingReason getTrackingReason() {
        return trackingReason;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }

    /** "Tracked by camera" or "Not tracked — No face profile", as the prototype's {@code trackingLabel}. */
    public String trackingLabel() {
        return trackingMode == TrackingMode.TRACKED ? "Tracked by camera" : "Not tracked — " + trackingReason.label();
    }

    /** Vocabulary "Employment type". */
    public enum EmploymentType {
        EMPLOYEE("Employee"),
        CONTRACTOR("Contractor"),
        INTERN("Intern");

        private final String label;

        EmploymentType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Vocabulary "Tracking mode". */
    public enum TrackingMode {
        TRACKED,
        NOT_TRACKED
    }

    /** Vocabulary "Tracking reason". */
    public enum TrackingReason {
        NO_FACE("No face profile"),
        NO_CAMERA("Location without a camera"),
        EXEMPT("Exempt");

        private final String label;

        TrackingReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
