package com.krztechnologies.attend.recognition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A recognition threshold profile TP-n (R14). Immutable: saving the Recognition page creates a new profile, and only
 * {@code active} ever changes (a database trigger refuses any change of code or values). Exactly one is active.
 */
@Entity
@Table(name = "threshold_profile")
public class ThresholdProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "profile_values", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String profileValues;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_label", updatable = false)
    private String createdByLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected ThresholdProfile() {}

    /** A new, inactive profile; {@code valuesJson} is the serialised {@link ThresholdValues}. */
    public ThresholdProfile(
            String code, String valuesJson, String note, UUID createdBy, String createdByLabel, Instant createdAt) {
        this.code = code;
        this.profileValues = valuesJson;
        this.note = note;
        this.createdBy = createdBy;
        this.createdByLabel = createdByLabel;
        this.createdAt = createdAt;
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getProfileValues() {
        return profileValues;
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

    public boolean isActive() {
        return active;
    }
}
