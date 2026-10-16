package com.krztechnologies.attend.access.domain;

import com.krztechnologies.attend.common.security.Permission;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** A KRZ Attend role. System Administrator is built-in and locked (always every permission); Enroller is built-in. */
@Entity
@Table(name = "role")
public class Role {

    public static final String SYSTEM_ADMINISTRATOR = "ROLE-SYSADMIN";
    public static final String ENROLLER = "ROLE-ENROLLER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, columnDefinition = "citext")
    private String name;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "built_in", nullable = false, updatable = false)
    private boolean builtIn;

    @Column(name = "locked", nullable = false, updatable = false)
    private boolean locked;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_by_label", updatable = false)
    private String createdByLabel;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_key")
    private Set<String> permissionKeys = new HashSet<>();

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Role() {}

    public Role(String code, String name, String description, Set<Permission> permissions, UUID createdBy) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        setPermissions(permissions);
    }

    public void update(String name, String description, Set<Permission> permissions) {
        this.name = name;
        this.description = description;
        setPermissions(permissions);
    }

    public boolean isSystemAdministrator() {
        return SYSTEM_ADMINISTRATOR.equals(code);
    }

    /** The locked System Administrator always resolves to every permission, whatever the table says. */
    public Set<Permission> permissions() {
        if (isSystemAdministrator()) {
            return EnumSet.allOf(Permission.class);
        }
        return permissionKeys.stream()
                .map(Permission::fromKey)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }

    private void setPermissions(Set<Permission> permissions) {
        permissionKeys.clear();
        permissions.forEach(p -> permissionKeys.add(p.key()));
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

    public boolean isBuiltIn() {
        return builtIn;
    }

    public boolean isLocked() {
        return locked;
    }

    public String getCreatedByLabel() {
        return createdByLabel;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getVersion() {
        return version;
    }
}
