package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.notification.domain.EmailOutbox;
import com.krztechnologies.attend.notification.domain.RenderedEmail;
import org.springframework.stereotype.Component;

/**
 * Hands an e-mail to the {@link MailSender}. MAIL_TEST_RECIPIENT redirects EVERY e-mail to one address and prefixes
 * the subject with {@code [DEV → original@…]}. Outside production that address is mandatory: without it nothing is
 * sent ({@code NO_TEST_RECIPIENT}), so real employees are never e-mailed from development.
 */
@Component
public class EmailDelivery {

    private final MailSender sender;
    private final MailProperties mail;
    private final AppProperties app;

    public EmailDelivery(MailSender sender, MailProperties mail, AppProperties app) {
        this.sender = sender;
        this.mail = mail;
        this.app = app;
    }

    /** One outbox e-mail. */
    public void deliver(EmailOutbox email) {
        deliver(email.getToAddress(), email.getSubject(), email.getBodyHtml(), email.getBodyText());
    }

    /** An e-mail that is not an outbox row of its own: the daily digest of several DIGESTED rows. */
    public void deliver(String to, RenderedEmail email) {
        deliver(to, email.subject(), email.html(), email.text());
    }

    private void deliver(String to, String subject, String html, String text) {
        String address = to;
        String line = subject;
        if (mail.testRecipient() != null) {
            line = "[DEV → " + to + "] " + subject;
            address = mail.testRecipient();
        } else if (!app.isProduction()) {
            throw new MailSender.MailDeliveryException(
                    "NO_TEST_RECIPIENT",
                    "Outside production e-mail goes only to MAIL_TEST_RECIPIENT, which is not set.",
                    null);
        }
        sender.send(new MailSender.OutgoingEmail(address, line, html, text));
    }
}
