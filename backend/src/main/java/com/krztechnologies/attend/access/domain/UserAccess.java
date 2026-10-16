package com.krztechnologies.attend.access.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A person with access to KRZ Attend and their roles (roles are owned by KRZ Attend, never by Entra). */
@Entity
@Table(name = "user_access")
public class UserAccess {

    @Id
    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "added_by", updatable = false)
    private UUID addedBy;

    @Column(name = "added_by_label", updatable = false)
    private String addedByLabel;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "note")
    private String note;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "employee_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected UserAccess() {}

    public UserAccess(UUID employeeId, UUID addedBy, String addedByLabel, Instant addedAt, Set<Role> roles) {
        this.employeeId = employeeId;
        this.addedBy = addedBy;
        this.addedByLabel = addedByLabel;
        this.addedAt = addedAt;
        this.roles.addAll(roles);
    }

    public void replaceRoles(Set<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    public boolean hasRole(String code) {
        return roles.stream().anyMatch(r -> r.getCode().equals(code));
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public String getAddedByLabel() {
        return addedByLabel;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public Integer getVersion() {
        return version;
    }
}
