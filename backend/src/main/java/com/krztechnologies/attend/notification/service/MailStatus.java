package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.notification.domain.EmailOutbox;
import com.krztechnologies.attend.notification.repository.EmailOutboxRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** E-mail delivery state for Settings → System: the last successful send and the number of final failures. */
@Service
public class MailStatus {

    private final EmailOutboxRepository outbox;

    public MailStatus(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * @param lastSentAt null when nothing was sent yet
     * @param failed e-mails that failed after every retry
     */
    public record State(Instant lastSentAt, long failed) {}

    @Transactional(readOnly = true)
    public State current() {
        return new State(outbox.lastSentAt().orElse(null), outbox.countByStatus(EmailOutbox.Status.FAILED));
    }
}
