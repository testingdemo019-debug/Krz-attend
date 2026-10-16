package com.krztechnologies.attend.notification.repository;

import com.krztechnologies.attend.notification.domain.EmailOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    /** PENDING e-mails whose scheduled time and retry time have come. */
    @Query("""
            SELECT e FROM EmailOutbox e
             WHERE e.status = com.krztechnologies.attend.notification.domain.EmailOutbox.Status.PENDING
               AND e.scheduledFor <= :now
               AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
             ORDER BY e.scheduledFor
             LIMIT 50
            """)
    List<EmailOutbox> findDue(Instant now);

    /** Recipients with DIGESTED e-mails whose digest time (and retry time) has come. */
    @Query("""
            SELECT DISTINCT e.recipientId FROM EmailOutbox e
             WHERE e.status = com.krztechnologies.attend.notification.domain.EmailOutbox.Status.DIGESTED
               AND e.recipientId IS NOT NULL
               AND e.scheduledFor <= :now
               AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
            """)
    List<UUID> findDigestRecipientsDue(Instant now);

    /** One recipient's due DIGESTED e-mails, oldest first (at most 200 go into one digest). */
    @Query("""
            SELECT e FROM EmailOutbox e
             WHERE e.status = com.krztechnologies.attend.notification.domain.EmailOutbox.Status.DIGESTED
               AND e.recipientId = :recipientId
               AND e.scheduledFor <= :now
               AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
             ORDER BY e.createdAt
             LIMIT 200
            """)
    List<EmailOutbox> findDueDigest(UUID recipientId, Instant now);

    @Query("SELECT max(e.sentAt) FROM EmailOutbox e WHERE e.sentAt IS NOT NULL")
    Optional<Instant> lastSentAt();

    long countByStatus(EmailOutbox.Status status);
}
