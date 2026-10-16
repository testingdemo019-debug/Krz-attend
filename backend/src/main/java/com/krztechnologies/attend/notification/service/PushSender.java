package com.krztechnologies.attend.notification.service;

import java.util.UUID;

/**
 * Mobile push. Only {@link DisabledPushSender} exists until a mobile app does (PENDING P-06); enabling push means one
 * new implementation and PUSH_ENABLED=true.
 */
public interface PushSender {

    void send(UUID recipientId, String title, String body, String link);
}
