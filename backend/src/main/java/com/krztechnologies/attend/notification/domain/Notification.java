package com.krztechnologies.attend.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** NT-nnnnnn: one in-app notification (the bell) for one recipient. */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "rule_code", nullable = false, updatable = false)
    private String ruleCode;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "body", updatable = false)
    private String body;

    @Column(name = "link", updatable = false)
    private String link;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false)
    private NotificationRule.Severity severity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {}

    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    public String getCode() {
        return code;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLink() {
        return link;
    }

    public NotificationRule.Severity getSeverity() {
        return severity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
