package com.krztechnologies.attend.notification.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** No mobile app yet: push is shown in the UI but disabled ("Mobile app not available yet"). PENDING P-06. */
@Component
public class DisabledPushSender implements PushSender {

    private static final Logger LOG = LoggerFactory.getLogger(DisabledPushSender.class);

    @Override
    public void send(UUID recipientId, String title, String body, String link) {
        LOG.debug("Push disabled: not sending to employeeId={}", recipientId);
    }
}
