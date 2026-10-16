package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.realtime.SseHub;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.notification.domain.EmailOutbox;
import com.krztechnologies.attend.notification.domain.NotificationRule;
import com.krztechnologies.attend.notification.domain.RenderedEmail;
import com.krztechnologies.attend.notification.repository.EmailOutboxRepository;
import com.krztechnologies.attend.notification.repository.NotificationRuleRepository;
import com.krztechnologies.attend.notification.repository.RecipientQueries;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a notification for a rule (NR-nn): resolves recipients, stores in-app notifications (deduplicated) and
 * pushes them over SSE after commit, queues e-mails (quiet hours and daily digest respected; CRITICAL ignores quiet
 * hours) and calls the (disabled) push sender. Everything is stored in the database.
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRuleRepository rules;
    private final RecipientQueries recipients;
    private final EmailOutboxRepository outbox;
    private final EmailComposer composer;
    private final PushSender push;
    private final FeatureFlags features;
    private final IdGenerator ids;
    private final TimeService time;
    private final SseHub sse;
    private final JdbcClient jdbc;

    public NotificationService(
            NotificationRuleRepository rules,
            RecipientQueries recipients,
            EmailOutboxRepository outbox,
            EmailComposer composer,
            PushSender push,
            FeatureFlags features,
            IdGenerator ids,
            TimeService time,
            SseHub sse,
            JdbcClient jdbc) {
        this.rules = rules;
        this.recipients = recipients;
        this.outbox = outbox;
        this.composer = composer;
        this.push = push;
        this.features = features;
        this.ids = ids;
        this.time = time;
        this.sse = sse;
        this.jdbc = jdbc;
    }

    /**
     * An in-app notification as the bell shows it.
     *
     * @param link app-relative path, or null
     */
    public record InAppNotification(
            String code, String ruleCode, String title, String body, String link, String severity, Instant createdAt) {}

    /** Returns the number of recipients notified in the app. */
    @Transactional
    public int publish(String ruleCode, NotificationRequest request) {
        NotificationRule rule = rules.findById(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification rule " + ruleCode));
        if (!rule.isEnabled()) {
            return 0;
        }
        Instant now = time.now();
        int notified = 0;
        for (RecipientQueries.Recipient recipient : recipients.recipientsOf(ruleCode)) {
            if (alreadyNotified(recipient, request.dedupeKey())) {
                continue;
            }
            if (rule.isInApp() && storeInApp(rule, recipient, request, now)) {
                notified++;
            }
            if (rule.isEmail() && features.mailEnabled()) {
                queueEmail(rule, recipient, request, now);
            }
            if (rule.isPush() && features.pushEnabled()) {
                push.send(recipient.employeeId(), request.title(), request.body(), request.link());
            }
        }
        LOG.info("Notification {} published to {} recipient(s)", ruleCode, notified);
        return notified;
    }

    /** A recipient gets one notification per dedupe key, across the app and e-mail. */
    private boolean alreadyNotified(RecipientQueries.Recipient recipient, String dedupeKey) {
        if (dedupeKey == null) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM attend.notification WHERE recipient_id = :r AND dedupe_key = :k)
                            OR EXISTS (SELECT 1 FROM attend.email_outbox WHERE recipient_id = :r AND dedupe_key = :k)
                        """)
                .param("r", recipient.employeeId())
                .param("k", dedupeKey)
                .query(Boolean.class)
                .single();
    }

    private boolean storeInApp(
            NotificationRule rule, RecipientQueries.Recipient recipient, NotificationRequest request, Instant now) {
        String code = ids.next(CodeType.NOTIFICATION);
        int inserted = jdbc.sql("""
                        INSERT INTO attend.notification (code, rule_code, recipient_id, title, body, link, severity, created_at, dedupe_key)
                        VALUES (:code, :rule, :recipient, :title, :body, :link, :severity, :createdAt, :dedupe)
                        ON CONFLICT (recipient_id, dedupe_key) DO NOTHING
                        """)
                .param("code", code)
                .param("rule", rule.getCode())
                .param("recipient", recipient.employeeId())
                .param("title", request.title())
                .param("body", request.body())
                .param("link", request.link())
                .param("severity", rule.getSeverity().name())
                .param("createdAt", Timestamp.from(now))
                .param("dedupe", request.dedupeKey())
                .update();
        if (inserted == 0) {
            return false;
        }
        var event = new InAppNotification(
                code,
                rule.getCode(),
                request.title(),
                request.body(),
                request.link(),
                rule.getSeverity().name(),
                now);
        AfterCommit.run(() -> sse.publishToUser(recipient.employeeId(), "notification.created", event));
        return true;
    }

    private void queueEmail(
            NotificationRule rule, RecipientQueries.Recipient recipient, NotificationRequest request, Instant now) {
        Map<String, Object> vars = new HashMap<>(request.params());
        vars.put("recipientName", recipient.displayName());
        vars.put("title", request.title());
        vars.put("body", request.body());
        vars.put("ruleLabel", rule.getLabel());
        RenderedEmail email = composer.compose("notification", request.title(), vars, request.link());
        boolean digest = rule.getDigest() == NotificationRule.Digest.DAILY;
        Instant when = digest ? nextOccurrence(rule.getDigestTime(), now) : deferForQuietHours(rule, now);
        outbox.save(new EmailOutbox(
                ids.next(CodeType.EMAIL),
                rule.getCode(),
                recipient.employeeId(),
                recipient.address(),
                email,
                digest ? EmailOutbox.Status.DIGESTED : EmailOutbox.Status.PENDING,
                when,
                request.dedupeKey(),
                now));
    }

    /** During the rule's quiet hours (IST), non-critical e-mails wait until the quiet hours end. */
    Instant deferForQuietHours(NotificationRule rule, Instant now) {
        if (rule.getSeverity() == NotificationRule.Severity.CRITICAL
                || rule.getQuietStart() == null
                || rule.getQuietEnd() == null) {
            return now;
        }
        LocalTime local = time.zoned(now).toLocalTime();
        LocalTime start = rule.getQuietStart();
        LocalTime end = rule.getQuietEnd();
        boolean quiet = start.isBefore(end)
                ? !local.isBefore(start) && local.isBefore(end)
                : !local.isBefore(start) || local.isBefore(end);
        return quiet ? nextOccurrence(end, now) : now;
    }

    private Instant nextOccurrence(LocalTime at, Instant now) {
        LocalDate today = time.dayOf(now);
        Instant candidate = time.at(today, at);
        return candidate.isAfter(now) ? candidate : time.at(today.plusDays(1), at);
    }
}
