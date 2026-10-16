package com.krztechnologies.attend.organisation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/** HOL-yyyy-nn (R4): only PUBLIC holidays change attendance; no location means all locations. */
@Entity
@Table(name = "holiday")
public class Holiday {

    /** PUBLIC changes attendance to "Holiday"; OPTIONAL is listed for reference only. */
    public enum Type {
        PUBLIC,
        OPTIONAL;

        /** "Public", "Optional" (the prototype's labels). */
        public String label() {
            return this == PUBLIC ? "Public" : "Optional";
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private Type type;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "is_sample", nullable = false)
    private boolean sample;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Holiday() {}

    /** @param source {@code LOCAL} or {@code CSV_IMPORT} */
    public Holiday(
            String code, LocalDate date, String name, Type type, Long locationId, String source, UUID createdBy) {
        this.code = code;
        this.date = date;
        this.name = name;
        this.type = type;
        this.locationId = locationId;
        this.source = source;
        this.createdBy = createdBy;
    }

    /** An edited holiday is no longer a sample. */
    public void update(LocalDate newDate, String newName, Type newType, Long newLocationId) {
        this.date = newDate;
        this.name = newName;
        this.type = newType;
        this.locationId = newLocationId;
        this.sample = false;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public Long getLocationId() {
        return locationId;
    }

    public String getSource() {
        return source;
    }

    public boolean isSample() {
        return sample;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }
}
