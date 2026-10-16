package com.krztechnologies.attend.notification.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** NR-nn: when people are told about what, through which channels (Settings → Notifications). */
@Entity
@Table(name = "notification_rule")
public class NotificationRule {

    @Id
    @Column(name = "code")
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "description", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "threshold")
    private Integer threshold;

    @Column(name = "threshold_unit")
    private String thresholdUnit;

    @Column(name = "params", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String params;

    @Column(name = "in_app", nullable = false)
    private boolean inApp;

    @Column(name = "email", nullable = false)
    private boolean email;

    @Column(name = "push", nullable = false)
    private boolean push;

    @Column(name = "quiet_start")
    private LocalTime quietStart;

    @Column(name = "quiet_end")
    private LocalTime quietEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "digest", nullable = false)
    private Digest digest;

    @Column(name = "digest_time")
    private LocalTime digestTime;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_rule_role", joinColumns = @JoinColumn(name = "rule_code"))
    @Column(name = "role_id")
    private Set<Long> roleIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_rule_user", joinColumns = @JoinColumn(name = "rule_code"))
    @Column(name = "employee_id")
    private Set<UUID> userIds = new HashSet<>();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected NotificationRule() {}

    /**
     * Applies an edit from Settings → Notifications (validated by the caller). Push stays off while there is no
     * mobile app (PENDING P-06).
     *
     * @param params the rule's parameters as a JSON object
     */
    public void update(Edit edit, String params, Set<Long> roleIds, Set<UUID> userIds, UUID updatedBy) {
        this.enabled = edit.enabled();
        this.threshold = edit.threshold();
        this.params = params;
        this.inApp = edit.inApp();
        this.email = edit.email();
        this.push = false;
        this.quietStart = edit.quietStart();
        this.quietEnd = edit.quietEnd();
        this.digest = edit.digest();
        this.digestTime = edit.digestTime();
        this.roleIds.clear();
        this.roleIds.addAll(roleIds);
        this.userIds.clear();
        this.userIds.addAll(userIds);
        this.updatedBy = updatedBy;
    }

    /** The editable values of a rule (recipients and parameters are passed separately). */
    public record Edit(
            boolean enabled,
            Integer threshold,
            boolean inApp,
            boolean email,
            LocalTime quietStart,
            LocalTime quietEnd,
            Digest digest,
            LocalTime digestTime) {}

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The threshold in {@link #getThresholdUnit()}, or null for rules without one. */
    public Integer getThreshold() {
        return threshold;
    }

    /** {@code MINUTES} or {@code DAYS}; null for rules without a threshold. */
    public String getThresholdUnit() {
        return thresholdUnit;
    }

    /** The parameters as a JSON object (e.g. NR-05 office hours). */
    public String getParams() {
        return params;
    }

    public boolean isInApp() {
        return inApp;
    }

    public boolean isEmail() {
        return email;
    }

    public boolean isPush() {
        return push;
    }

    public LocalTime getQuietStart() {
        return quietStart;
    }

    public LocalTime getQuietEnd() {
        return quietEnd;
    }

    public Digest getDigest() {
        return digest;
    }

    public LocalTime getDigestTime() {
        return digestTime;
    }

    public Set<Long> getRoleIds() {
        return Set.copyOf(roleIds);
    }

    public Set<UUID> getUserIds() {
        return Set.copyOf(userIds);
    }

    public Integer getVersion() {
        return version;
    }

    /** Notification severity: CRITICAL ignores quiet hours. */
    public enum Severity {
        INFO,
        WARN,
        CRITICAL
    }

    /** Delivery: immediately, or collected into one daily e-mail. */
    public enum Digest {
        NONE,
        DAILY
    }
}
