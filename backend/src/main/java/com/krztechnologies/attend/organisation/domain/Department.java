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
import java.util.UUID;

/** A department (SUP "Support"): groups employees and can give all of them one shift. The head is a member. */
@Entity
@Table(name = "department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false, columnDefinition = "citext")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "head_id")
    private UUID headId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RecordStatus status;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Department() {}

    public Department(String code, String name, String description, UUID headId, UUID createdBy) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.headId = headId;
        this.status = RecordStatus.ACTIVE;
        this.source = "LOCAL";
        this.createdBy = createdBy;
    }

    public void update(String newCode, String newName, String newDescription, UUID newHeadId) {
        this.code = newCode;
        this.name = newName;
        this.description = newDescription;
        this.headId = newHeadId;
    }

    public void setStatus(RecordStatus newStatus) {
        this.status = newStatus;
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

    public String getDescription() {
        return description;
    }

    public UUID getHeadId() {
        return headId;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public String getSource() {
        return source;
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
