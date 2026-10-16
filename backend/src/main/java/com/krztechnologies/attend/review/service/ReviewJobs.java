package com.krztechnologies.attend.review.service;

import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import com.krztechnologies.attend.notification.service.RuleSettings;
import com.krztechnologies.attend.review.repository.ReviewStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** NR-03 every minute: an open review item waiting longer than the rule's threshold notifies once. */
@Component
public class ReviewJobs {

    private static final int NR03_DEFAULT_MINUTES = 30;

    private final ReviewStore store;
    private final NotificationService notifications;
    private final RuleSettings rules;
    private final TimeService time;
    private final TransactionTemplate transactions;

    public ReviewJobs(
            ReviewStore store,
            NotificationService notifications,
            RuleSettings rules,
            TimeService time,
            TransactionTemplate transactions) {
        this.store = store;
        this.notifications = notifications;
        this.rules = rules;
        this.time = time;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    @SchedulerLock(name = "review-waiting", lockAtMostFor = "PT5M")
    public void notifyWaiting() {
        int minutes = rules.threshold("NR-03", NR03_DEFAULT_MINUTES);
        Instant now = time.now();
        for (ReviewStore.Item item : store.waitingSince(now.minus(Duration.ofMinutes(minutes)))) {
            transactions.executeWithoutResult(status -> {
                store.markWaitNotified(item.id(), now);
                notifications.publish(
                        "NR-03",
                        new NotificationRequest(
                                "Review item waiting over " + minutes + " min",
                                item.code() + " · " + ReviewService.TYPE_LABELS.get(item.type()),
                                "/review?item=" + item.code(),
                                "NR-03:" + item.code(),
                                Map.of("item", item.code())));
            });
        }
    }
}
