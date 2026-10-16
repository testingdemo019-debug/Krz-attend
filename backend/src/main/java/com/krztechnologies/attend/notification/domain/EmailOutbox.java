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

/** EM-nnnnnn: a rendered e-mail waiting for, or done with, Microsoft Graph sendMail. */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    /** Backoff between attempts (1, 5, 15, 60 min); the 5th failure is final. */
    private static final int[] BACKOFF_MINUTES = {1, 5, 15, 60};

    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "rule_code", updatable = false)
    private String ruleCode;

    @Column(name = "recipient_id", updatable = false)
    private UUID recipientId;

    @Column(name = "to_address", nullable = false, updatable = false, columnDefinition = "citext")
    private String toAddress;

    @Column(name = "template_key", nullable = false, updatable = false)
    private String templateKey;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "body_html", nullable = false, updatable = false)
    private String bodyHtml;

    @Column(name = "body_text", nullable = false, updatable = false)
    private String bodyText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Column(name = "attempts", nullable = false)
    private Short attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "dedupe_key", updatable = false)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailOutbox() {}

    public EmailOutbox(
            String code,
            String ruleCode,
            UUID recipientId,
            String toAddress,
            RenderedEmail email,
            Status status,
            Instant scheduledFor,
            String dedupeKey,
            Instant createdAt) {
        this.code = code;
        this.ruleCode = ruleCode;
        this.recipientId = recipientId;
        this.toAddress = toAddress;
        this.templateKey = email.templateKey();
        this.subject = email.subject();
        this.bodyHtml = email.html();
        this.bodyText = email.text();
        this.status = status;
        this.scheduledFor = scheduledFor;
        this.attempts = 0;
        this.dedupeKey = dedupeKey;
        this.createdAt = createdAt;
    }

    public void markSent(Instant at) {
        this.status = Status.SENT;
        this.sentAt = at;
        this.attempts = (short) (attempts + 1);
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    /**
     * Records a failed attempt; schedules the next one or gives up after {@link #MAX_ATTEMPTS}. A DIGESTED row keeps
     * its status while it is retried (with its digest), then becomes FAILED like any other.
     */
    public void markFailedAttempt(Instant at, String error) {
        this.attempts = (short) (attempts + 1);
        this.lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            this.status = Status.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt =
                    at.plusSeconds(60L * BACKOFF_MINUTES[Math.min(attempts - 1, BACKOFF_MINUTES.length - 1)]);
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getToAddress() {
        return toAddress;
    }

    public String getSubject() {
        return subject;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public String getBodyText() {
        return bodyText;
    }

    public Status getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    /** E-mail outbox status. */
    public enum Status {
        PENDING,
        SENT,
        FAILED,
        SUPPRESSED,
        DIGESTED
    }
}
