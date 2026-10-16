package com.krztechnologies.attend.employee.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** An employee's identity, synced from Microsoft Entra ID. The key is (tenant ID, object ID), never e-mail or UPN. */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entra_tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "entra_object_id", nullable = false, updatable = false)
    private UUID objectId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "given_name")
    private String givenName;

    @Column(name = "surname")
    private String surname;

    @Column(name = "upn", nullable = false, columnDefinition = "citext")
    private String upn;

    @Column(name = "mail", columnDefinition = "citext")
    private String mail;

    @Column(name = "account_enabled", nullable = false)
    private boolean accountEnabled;

    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "left_on")
    private LocalDate leftOn;

    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Employee() {}

    public Employee(UUID tenantId, UUID objectId, String displayName, String upn, boolean accountEnabled) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.objectId = Objects.requireNonNull(objectId);
        this.displayName = Objects.requireNonNull(displayName);
        this.upn = Objects.requireNonNull(upn);
        this.accountEnabled = accountEnabled;
    }

    /** Applies directory values; returns true when anything visible changed. */
    public boolean updateIdentity(String displayName, String givenName, String surname, String upn, String mail) {
        boolean changed = !Objects.equals(this.displayName, displayName)
                || !Objects.equals(this.givenName, givenName)
                || !Objects.equals(this.surname, surname)
                || !Objects.equals(this.upn, upn)
                || !Objects.equals(this.mail, mail);
        this.displayName = displayName;
        this.givenName = givenName;
        this.surname = surname;
        this.upn = upn;
        this.mail = mail;
        return changed;
    }

    public void disable(String reason, LocalDate leftOn) {
        this.accountEnabled = false;
        this.disabledReason = reason;
        this.leftOn = leftOn;
    }

    public void enable() {
        this.accountEnabled = true;
        this.disabledReason = null;
        this.leftOn = null;
    }

    public void markSynced(Instant at) {
        this.syncedAt = at;
    }

    public void markSignedIn(Instant at) {
        this.lastSignInAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getObjectId() {
        return objectId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getGivenName() {
        return givenName;
    }

    public String getSurname() {
        return surname;
    }

    public String getUpn() {
        return upn;
    }

    public String getMail() {
        return mail;
    }

    public boolean isAccountEnabled() {
        return accountEnabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public LocalDate getLeftOn() {
        return leftOn;
    }

    public Instant getLastSignInAt() {
        return lastSignInAt;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}
