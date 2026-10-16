package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.notification.domain.EmailOutbox;
import com.krztechnologies.attend.notification.repository.EmailOutboxRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every 15 s: sends the due PENDING e-mails, each in its own transaction so one failure never blocks the others.
 * Retries back off 1/5/15/60 min; the 5th failure is FAILED (shown in Settings → System).
 */
@Component
public class EmailSenderJob {

    private static final Logger LOG = LoggerFactory.getLogger(EmailSenderJob.class);

    private final EmailOutboxRepository outbox;
    private final EmailDelivery delivery;
    private final TimeService time;
    private final TransactionTemplate transactions;

    public EmailSenderJob(
            EmailOutboxRepository outbox, EmailDelivery delivery, TimeService time, TransactionTemplate transactions) {
        this.outbox = outbox;
        this.delivery = delivery;
        this.time = time;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @SchedulerLock(name = "email-sender", lockAtMostFor = "PT5M")
    public void sendDue() {
        var due = transactions.execute(status -> outbox.findDue(time.now()));
        if (due == null || due.isEmpty()) {
            return;
        }
        int sent = 0;
        int failed = 0;
        for (EmailOutbox email : due) {
            boolean ok = Boolean.TRUE.equals(transactions.execute(status -> sendOne(email)));
            if (ok) {
                sent++;
            } else {
                failed++;
            }
        }
        LOG.info("E-mail sender: {} sent, {} failed", sent, failed);
    }

    private boolean sendOne(EmailOutbox email) {
        try {
            delivery.deliver(email);
            email.markSent(time.now());
            outbox.save(email);
            return true;
        } catch (MailSender.MailDeliveryException e) {
            email.markFailedAttempt(time.now(), e.code() + ": " + e.getMessage());
            outbox.save(email);
            LOG.warn("E-mail {} failed ({}), status {}", email.getCode(), e.code(), email.getStatus());
            return false;
        }
    }
}
