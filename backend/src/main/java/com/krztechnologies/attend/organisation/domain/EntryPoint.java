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

/** EP-nn: a door or gate of a location; cameras are mounted at entry points. */
@Entity
@Table(name = "entry_point")
public class EntryPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "location_id", nullable = false, updatable = false)
    private Long locationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected EntryPoint() {}

    public EntryPoint(String code, Long locationId, String name) {
        this.code = code;
        this.locationId = locationId;
        this.name = name;
        this.status = RecordStatus.ACTIVE;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void archive() {
        this.status = RecordStatus.ARCHIVED;
    }

    public boolean isActive() {
        return status == RecordStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Long getLocationId() {
        return locationId;
    }

    public String getName() {
        return name;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }
}
