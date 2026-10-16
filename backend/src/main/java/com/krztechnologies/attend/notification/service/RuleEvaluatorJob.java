package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.notification.domain.NotificationRule;
import com.krztechnologies.attend.notification.repository.NotificationRuleRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute: the threshold rules that watch a state rather than an event. Here NR-09 (the Entra client secret
 * expires within the threshold, from ENTRA_CLIENT_SECRET_EXPIRES); it notifies at most once per IST day until the
 * secret is renewed. NR-01/NR-10 are evaluated by the camera health service and NR-03 by the review module, where
 * those states live.
 */
@Component
public class RuleEvaluatorJob {

    static final String SECRET_EXPIRY_RULE = "NR-09";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final NotificationRuleRepository rules;
    private final NotificationService notifications;
    private final EntraProperties entra;
    private final TimeService time;

    public RuleEvaluatorJob(
            NotificationRuleRepository rules,
            NotificationService notifications,
            EntraProperties entra,
            TimeService time) {
        this.rules = rules;
        this.notifications = notifications;
        this.entra = entra;
        this.time = time;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @SchedulerLock(name = "notification-rules", lockAtMostFor = "PT5M")
    public void evaluate() {
        checkSecretExpiry();
    }

    private void checkSecretExpiry() {
        LocalDate expires = entra.clientSecretExpires();
        NotificationRule rule = rules.findById(SECRET_EXPIRY_RULE).orElse(null);
        if (expires == null || rule == null || !rule.isEnabled() || rule.getThreshold() == null) {
            return;
        }
        LocalDate today = time.today();
        long days = ChronoUnit.DAYS.between(today, expires);
        if (days > rule.getThreshold()) {
            return;
        }
        String title;
        if (days < 0) {
            title = "Microsoft sign-in secret has expired";
        } else if (days == 0) {
            title = "Microsoft sign-in secret expires today";
        } else {
            title = "Microsoft sign-in secret expires in " + days + (days == 1 ? " day" : " days");
        }
        String body = "The Entra client secret " + (days < 0 ? "expired on " : "expires on ") + DAY.format(expires)
                + ". Create a new secret in Entra ID, put it and its expiry date into deploy/.env, and restart.";
        notifications.publish(
                SECRET_EXPIRY_RULE,
                new NotificationRequest(
                        title, body, "/settings/signin", SECRET_EXPIRY_RULE + ":" + expires + ":" + today, Map.of()));
    }
}
