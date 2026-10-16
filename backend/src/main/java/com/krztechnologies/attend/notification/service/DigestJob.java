package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.notification.domain.EmailOutbox;
import com.krztechnologies.attend.notification.domain.NotificationRule;
import com.krztechnologies.attend.notification.domain.RenderedEmail;
import com.krztechnologies.attend.notification.repository.EmailOutboxRepository;
import com.krztechnologies.attend.notification.repository.NotificationRuleRepository;
import com.krztechnologies.attend.notification.repository.RecipientQueries;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every minute: rules set to a daily digest store their e-mails as DIGESTED, scheduled for the rule's digest time
 * (IST). Once that time has come, each recipient gets ONE e-mail listing all their due notifications. On success the
 * collected rows become SENT (sent_at = when the digest went out); a failure counts as an attempt on every collected
 * row, which is retried with the outbox back-off (1/5/15/60 min) and becomes FAILED after the 5th attempt. One
 * transaction per recipient, so one failure never blocks the others.
 */
@Component
public class DigestJob {

    private static final Logger LOG = LoggerFactory.getLogger(DigestJob.class);

    private final EmailOutboxRepository outbox;
    private final NotificationRuleRepository rules;
    private final RecipientQueries recipients;
    private final EmailComposer composer;
    private final EmailDelivery delivery;
    private final SettingsService settings;
    private final TimeService time;
    private final TransactionTemplate transactions;

    public DigestJob(
            EmailOutboxRepository outbox,
            NotificationRuleRepository rules,
            RecipientQueries recipients,
            EmailComposer composer,
            EmailDelivery delivery,
            SettingsService settings,
            TimeService time,
            TransactionTemplate transactions) {
        this.outbox = outbox;
        this.rules = rules;
        this.recipients = recipients;
        this.composer = composer;
        this.delivery = delivery;
        this.settings = settings;
        this.time = time;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    @SchedulerLock(name = "email-digest", lockAtMostFor = "PT10M")
    public void sendDue() {
        List<UUID> due = transactions.execute(status -> outbox.findDigestRecipientsDue(time.now()));
        if (due == null || due.isEmpty()) {
            return;
        }
        int sent = 0;
        for (UUID recipientId : due) {
            if (Boolean.TRUE.equals(transactions.execute(status -> sendDigest(recipientId)))) {
                sent++;
            }
        }
        LOG.info("Daily digest: {} of {} recipient(s) sent", sent, due.size());
    }

    private boolean sendDigest(UUID recipientId) {
        Instant now = time.now();
        List<EmailOutbox> rows = outbox.findDueDigest(recipientId, now);
        if (rows.isEmpty()) {
            return false;
        }
        RenderedEmail email = compose(recipientId, rows);
        // The latest row has the address the person had when the last notification was queued.
        String to = rows.getLast().getToAddress();
        try {
            delivery.deliver(to, email);
            rows.forEach(row -> row.markSent(now));
            return true;
        } catch (MailSender.MailDeliveryException e) {
            rows.forEach(row -> row.markFailedAttempt(now, e.code() + ": " + e.getMessage()));
            LOG.warn("Daily digest for employeeId={} failed ({}), {} e-mail(s)", recipientId, e.code(), rows.size());
            return false;
        } finally {
            outbox.saveAll(rows);
        }
    }

    private RenderedEmail compose(UUID recipientId, List<EmailOutbox> rows) {
        Map<String, String> labels = rules.findAll().stream()
                .collect(Collectors.toMap(NotificationRule::getCode, NotificationRule::getLabel));
        DateTimeFormatter format = DateTimeFormatter.ofPattern(
                "12h".equals(settings.general().timeFormat()) ? "d MMM yyyy, h:mm a" : "d MMM yyyy, HH:mm",
                Locale.ENGLISH);
        List<Map<String, String>> items = rows.stream()
                .map(row -> Map.of(
                        "time", time.zoned(row.getCreatedAt()).format(format),
                        "rule", labels.getOrDefault(row.getRuleCode(), row.getRuleCode()),
                        "title", row.getSubject()))
                .toList();
        String subject =
                "KRZ Attend daily digest · " + rows.size() + (rows.size() == 1 ? " notification" : " notifications");
        return composer.compose(
                "digest",
                subject,
                Map.of(
                        "recipientName", recipients.self(recipientId).displayName(),
                        "items", items,
                        "count", rows.size()),
                "/");
    }
}
