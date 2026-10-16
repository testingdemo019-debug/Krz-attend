package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.notification.domain.EmailOutbox;
import com.krztechnologies.attend.notification.repository.EmailOutboxRepository;
import com.krztechnologies.attend.notification.repository.RecipientQueries;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Send test e-mail" (Settings → Notifications): sends to the signed-in user right away and reports the provider's exact
 * error code (e.g. {@code ErrorAccessDenied} = mailbox scoping or permission issue).
 */
@Service
public class TestEmailService {

    private final RecipientQueries recipients;
    private final EmailComposer composer;
    private final EmailDelivery delivery;
    private final EmailOutboxRepository outbox;
    private final IdGenerator ids;
    private final TimeService time;
    private final FeatureFlags features;
    private final MailProperties mail;
    private final AuditService audit;

    public TestEmailService(
            RecipientQueries recipients,
            EmailComposer composer,
            EmailDelivery delivery,
            EmailOutboxRepository outbox,
            IdGenerator ids,
            TimeService time,
            FeatureFlags features,
            MailProperties mail,
            AuditService audit) {
        this.recipients = recipients;
        this.composer = composer;
        this.delivery = delivery;
        this.outbox = outbox;
        this.ids = ids;
        this.time = time;
        this.features = features;
        this.mail = mail;
        this.audit = audit;
    }

    /**
     * @param sent whether the provider accepted it
     * @param errorCode the provider's error code when not sent
     * @param deliveredTo where it actually went (the test recipient in development), shown to the user who asked
     */
    public record TestEmailResult(
            String code,
            boolean sent,
            @Nullable String errorCode,
            String message,
            @Nullable String deliveredTo) {}

    @Transactional
    public TestEmailResult sendToSelf(UUID employeeId) {
        if (!features.mailEnabled()) {
            throw new Errors.BusinessRule("MAIL_DISABLED", "E-mail is turned off (MAIL_ENABLED=false).");
        }
        RecipientQueries.Recipient me = recipients.self(employeeId);
        var email = composer.compose(
                "test", "KRZ Attend test e-mail", Map.of("recipientName", me.displayName()), "/settings/notifications");
        EmailOutbox row = new EmailOutbox(
                ids.next(CodeType.EMAIL),
                null,
                employeeId,
                me.address(),
                email,
                EmailOutbox.Status.PENDING,
                time.now(),
                null,
                time.now());
        String deliveredTo = mail.testRecipient() != null ? mail.testRecipient() : me.address();
        TestEmailResult result;
        try {
            delivery.deliver(row);
            row.markSent(time.now());
            result = new TestEmailResult(row.getCode(), true, null, "Test e-mail sent.", deliveredTo);
        } catch (MailSender.MailDeliveryException e) {
            row.markFailedAttempt(time.now(), e.code() + ": " + e.getMessage());
            result = new TestEmailResult(row.getCode(), false, e.code(), e.getMessage(), deliveredTo);
        }
        outbox.save(row);
        audit.record(
                AuditCategory.SETTINGS,
                "Sent test e-mail",
                row.getCode(),
                null,
                result.sent() ? "Sent" : "Failed: " + result.errorCode(),
                null);
        return result;
    }
}
