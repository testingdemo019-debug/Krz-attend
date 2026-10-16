package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.notification.domain.Notification;
import com.krztechnologies.attend.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The user's own in-app notifications (the bell). */
@Service
public class InboxService {

    private final NotificationRepository notifications;
    private final TimeService time;

    public InboxService(NotificationRepository notifications, TimeService time) {
        this.notifications = notifications;
        this.time = time;
    }

    /**
     * One notification in the bell.
     *
     * @param read whether the user has read it
     */
    public record InboxItem(
            String code,
            String ruleCode,
            String title,
            @Nullable String body,
            @Nullable String link,
            String severity,
            Instant createdAt,
            boolean read) {}

    @Transactional(readOnly = true)
    public PageResponse<InboxItem> list(UUID recipientId, boolean unreadOnly, Pageable pageable) {
        var page = unreadOnly
                ? notifications.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(recipientId, pageable)
                : notifications.findByRecipientIdOrderByCreatedAtDesc(recipientId, pageable);
        return PageResponse.of(page, InboxService::toItem);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientId) {
        return notifications.countByRecipientIdAndReadAtIsNull(recipientId);
    }

    @Transactional
    public void markRead(UUID recipientId, String code) {
        Notification notification = notifications
                .findByCodeAndRecipientId(code, recipientId)
                .orElseThrow(() -> new Errors.NotFound("Notification not found."));
        notification.markRead(time.now());
    }

    @Transactional
    public int markAllRead(UUID recipientId) {
        return notifications.markAllRead(recipientId, time.now());
    }

    private static InboxItem toItem(Notification n) {
        return new InboxItem(
                n.getCode(),
                n.getRuleCode(),
                n.getTitle(),
                n.getBody(),
                n.getLink(),
                n.getSeverity().name(),
                n.getCreatedAt(),
                n.getReadAt() != null);
    }
}
