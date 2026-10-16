package com.krztechnologies.attend.notification.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * E-mail through Microsoft Graph sendMail (MAIL_* in deploy/.env).
 *
 * @param sender the mailbox the app sends as (attend-noreply@…)
 * @param testRecipient development only: every e-mail goes only here
 */
@Validated
@ConfigurationProperties("krz.mail")
public record MailProperties(
        boolean enabled,
        @NotNull Provider provider,
        @NotBlank String sender,
        String testRecipient) {

    public MailProperties {
        testRecipient = testRecipient == null || testRecipient.isBlank() ? null : testRecipient.trim();
    }

    /** MAIL_PROVIDER values. */
    public enum Provider {
        GRAPH,
        LOG
    }
}
