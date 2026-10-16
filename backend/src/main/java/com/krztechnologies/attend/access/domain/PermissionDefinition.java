package com.krztechnologies.attend.access.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** A row of the permission catalogue (seeded, read-only): key, group, label and description for the role editor. */
@Entity
@Immutable
@Table(name = "permission")
public class PermissionDefinition {

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    protected PermissionDefinition() {}

    public String getKey() {
        return key;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public Short getSortOrder() {
        return sortOrder;
    }
}
