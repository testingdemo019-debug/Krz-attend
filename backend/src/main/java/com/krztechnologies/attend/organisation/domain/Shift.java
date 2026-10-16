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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A shift (GEN "General"). Its timings and rules live in effective-dated {@link ShiftVersion}s (R10); exactly one shift
 * is the organisation default (R1) and the default can never be archived.
 */
@Entity
@Table(name = "shift")
public class Shift {

    /** The prototype's swatch palette (R10). */
    public static final List<String> PALETTE =
            List.of("#2563eb", "#0d9488", "#b45309", "#7c3aed", "#be185d", "#15803d", "#4b5563");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, columnDefinition = "citext")
    private String name;

    @Column(name = "colour", nullable = false)
    private String colour;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordStatus status;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Shift() {}

    public Shift(String code, String name, String colour, UUID createdBy) {
        this.code = code;
        this.name = name;
        this.colour = colour;
        this.status = RecordStatus.ACTIVE;
        this.source = "LOCAL";
        this.createdBy = createdBy;
    }

    public void update(String newName, String newColour) {
        this.name = newName;
        this.colour = newColour;
    }

    public void setStatus(RecordStatus newStatus) {
        this.status = newStatus;
    }

    public void setDefault(boolean value) {
        this.isDefault = value;
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

    public String getName() {
        return name;
    }

    public String getColour() {
        return colour;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public String getSource() {
        return source;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }
}
