package com.krztechnologies.attend.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MAIL_PROVIDER=log: nothing is sent; one log line per e-mail (no address, no body). */
@Component
@ConditionalOnProperty(name = "krz.mail.provider", havingValue = "log")
public class LogOnlyMailSender implements MailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LogOnlyMailSender.class);

    @Override
    public void send(OutgoingEmail email) {
        LOG.info(
                "MAIL_PROVIDER=log: e-mail not sent ({} characters of HTML)",
                email.html().length());
    }
}
