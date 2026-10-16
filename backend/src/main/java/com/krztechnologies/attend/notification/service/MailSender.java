package com.krztechnologies.attend.notification.service;

/**
 * Sends one e-mail. Implementations: {@link GraphMailSender} (Microsoft Graph sendMail, MAIL_PROVIDER=graph) and
 * {@link LogOnlyMailSender} (MAIL_PROVIDER=log). Throws {@link MailDeliveryException} on failure.
 */
public interface MailSender {

    void send(OutgoingEmail email);

    /** An e-mail ready to go (after the development test-recipient redirect). */
    record OutgoingEmail(String to, String subject, String html, String text) {}

    /**
     * Sending failed.
     *
     * @param code the provider's error code (e.g. Graph's {@code ErrorAccessDenied})
     */
    final class MailDeliveryException extends RuntimeException {
        private final String code;

        public MailDeliveryException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
