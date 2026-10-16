package com.krztechnologies.attend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A published version of the consent notice employees agree to before enrolment. Published notices are never edited:
 * publishing a new version deactivates the previous one, so exactly one is active.
 */
@Entity
@Table(name = "consent_notice")
public class ConsentNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The notice version (v1, v2, …), not an optimistic-locking counter. */
    @Column(name = "version", nullable = false, updatable = false)
    private int noticeVersion;

    @Column(name = "text", nullable = false, updatable = false)
    private String text;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "published_by", updatable = false)
    private UUID publishedBy;

    @Column(name = "published_by_label", updatable = false)
    private String publishedByLabel;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected ConsentNotice() {}

    public ConsentNotice(
            int noticeVersion, String text, Instant publishedAt, UUID publishedBy, String publishedByLabel) {
        this.noticeVersion = noticeVersion;
        this.text = text;
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
        this.publishedByLabel = publishedByLabel;
        this.active = true;
    }

    /** Superseded by a newer version. */
    public void deactivate() {
        this.active = false;
    }

    public int getNoticeVersion() {
        return noticeVersion;
    }

    public String getText() {
        return text;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedByLabel() {
        return publishedByLabel;
    }

    public boolean isActive() {
        return active;
    }
}
