package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.graph.GraphClient;
import com.krztechnologies.attend.common.graph.GraphException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends as {@code MAIL_SENDER} (attend-noreply@…) with Graph {@code POST /users/{sender}/sendMail}, not saved to Sent
 * Items. The app's Mail.Send grant must be restricted to that mailbox (PENDING P-03).
 */
@Component
@ConditionalOnProperty(name = "krz.mail.provider", havingValue = "graph", matchIfMissing = true)
public class GraphMailSender implements MailSender {

    private final GraphClient graph;
    private final MailProperties mail;

    public GraphMailSender(GraphClient graph, MailProperties mail) {
        this.graph = graph;
        this.mail = mail;
    }

    @Override
    public void send(OutgoingEmail email) {
        Map<String, Object> message = Map.of(
                "subject", email.subject(),
                "body", Map.of("contentType", "HTML", "content", email.html()),
                "toRecipients", List.of(Map.of("emailAddress", Map.of("address", email.to()))));
        try {
            graph.post(
                    "/users/" + URLEncoder.encode(mail.sender(), StandardCharsets.UTF_8) + "/sendMail",
                    Map.of("message", message, "saveToSentItems", false));
        } catch (GraphException e) {
            throw new MailDeliveryException(
                    e.code(), "Microsoft Graph refused the e-mail (HTTP " + e.status() + ")", e);
        }
    }
}
