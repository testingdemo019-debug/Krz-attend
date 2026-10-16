package com.krztechnologies.attend.hrms.service;

import com.krztechnologies.attend.common.settings.HrmsSettings;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.hrms.repository.OutboxStore;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R8's sender: delivers the due PENDING outbox entries to KRZ HRMS, oldest first, each in its own transaction with the
 * row locked so it is never sent twice. A failed attempt waits the next back-off (5, 15, 30, 60, 120 s by default);
 * after the last attempt the entry is FAILED and NR-04 tells the administrators. Runs only while HRMS is enabled
 * ({@link HrmsSenderJob}).
 */
@Service
public class HrmsSender {

    private static final Logger LOG = LoggerFactory.getLogger(HrmsSender.class);

    private static final int BATCH = 100;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxStore store;
    private final HrmsClient client;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final TimeService time;
    private final TransactionTemplate transactions;

    public HrmsSender(
            OutboxStore store,
            HrmsClient client,
            SettingsService settings,
            NotificationService notifications,
            TimeService time,
            TransactionTemplate transactions) {
        this.store = store;
        this.client = client;
        this.settings = settings;
        this.notifications = notifications;
        this.time = time;
        this.transactions = transactions;
    }

    private enum Outcome {
        SENT,
        RETRY,
        FAILED,
        SKIPPED
    }

    /** Sends every entry due now, batch after batch; returns how many reached HRMS. */
    public int sendDue() {
        HrmsSettings retries = settings.hrms();
        Instant now = time.now();
        int sent = 0;
        List<String> failed = new ArrayList<>();
        Set<Long> attempted = new HashSet<>();
        List<Long> batch = store.dueIds(now, BATCH);
        while (!batch.isEmpty()) {
            for (long id : batch) {
                attempted.add(id);
                Outcome outcome = transactions.execute(status -> attempt(id, now, retries, failed));
                if (outcome == Outcome.SENT) {
                    sent++;
                }
            }
            // An entry waiting for an older one of its punch ID becomes due once that one was sent.
            batch = store.dueIds(now, BATCH).stream()
                    .filter(id -> !attempted.contains(id))
                    .toList();
        }
        if (!failed.isEmpty()) {
            transactions.executeWithoutResult(status -> notifications.publish(
                    "NR-04",
                    new NotificationRequest(
                            failed.size() + (failed.size() == 1 ? " push" : " pushes") + " failed to reach KRZ HRMS",
                            "Retries exhausted. Open the outbox to retry.",
                            "/settings/hrms",
                            "NR-04:" + failed.getFirst(),
                            Map.of("entries", String.join(", ", failed)))));
        }
        return sent;
    }

    private Outcome attempt(long id, Instant now, HrmsSettings retries, List<String> failed) {
        Optional<OutboxStore.Due> due = store.lockDue(id, now);
        if (due.isEmpty()) {
            return Outcome.SKIPPED;
        }
        OutboxStore.Due entry = due.get();
        int attempts = entry.attempts() + 1;
        String error = deliver(entry);
        Instant at = time.now();
        if (error == null) {
            store.markSent(entry.id(), attempts, at);
            return Outcome.SENT;
        }
        if (attempts >= retries.maxAttempts()) {
            store.markAttemptFailed(entry.id(), attempts, error, at, null);
            failed.add(entry.code());
            return Outcome.FAILED;
        }
        List<Integer> backoff = retries.backoffSeconds();
        int wait = backoff.get(Math.min(attempts, backoff.size()) - 1);
        store.markAttemptFailed(entry.id(), attempts, error, at, at.plusSeconds(wait));
        return Outcome.RETRY;
    }

    /** Null when HRMS took the entry, else why not (HRMS is an external boundary: nothing escapes from here). */
    private String deliver(OutboxStore.Due entry) {
        try {
            client.deliver(new HrmsClient.Delivery(entry.code(), entry.kind(), entry.punchId(), entry.payload()));
            return null;
        } catch (HrmsClient.DeliveryFailed e) {
            return truncate(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("KRZ HRMS delivery of {} failed unexpectedly", entry.code(), e);
            return truncate("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private static String truncate(String message) {
        String text = message == null || message.isBlank() ? "No reason given" : message;
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
