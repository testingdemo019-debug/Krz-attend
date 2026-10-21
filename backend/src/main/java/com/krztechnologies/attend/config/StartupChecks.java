package com.krztechnologies.attend.config;

import com.krztechnologies.attend.access.service.UserAccessService;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.notification.service.MailProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Start-up guards: the dev profile can never run with APP_ENV=prod; a leftover BOOTSTRAP_ADMIN_EMAILS is reported once
 * an administrator exists (it is ignored from then on); outside production a missing MAIL_TEST_RECIPIENT is reported
 * (e-mails are held until it is set), and in production a leftover one (every e-mail would still go only to it).
 */
@Component
public class StartupChecks {

    private static final Logger LOG = LoggerFactory.getLogger(StartupChecks.class);

    private final AppProperties app;
    private final MailProperties mail;
    private final Environment environment;
    private final UserAccessService access;

    public StartupChecks(AppProperties app, MailProperties mail, Environment environment, UserAccessService access) {
        this.app = app;
        this.mail = mail;
        this.environment = environment;
        this.access = access;
        if (app.isProduction() && environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("The 'dev' profile is active while APP_ENV=prod. Refusing to start.");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnAboutBootstrapAdmins() {
        if (!app.bootstrapAdminEmails().isEmpty() && access.administratorExists()) {
            LOG.warn("BOOTSTRAP_ADMIN_EMAILS is still set although a System Administrator exists; it is ignored. "
                    + "Remove it from deploy/.env.");
        }
        mailWarning(app, mail).ifPresent(warning -> LOG.warn(warning));
        LOG.info(
                "KRZ Attend {} started (APP_ENV={}, profiles={})",
                app.version(),
                app.env(),
                String.join(",", environment.getActiveProfiles()));
    }

    /** What is wrong with MAIL_TEST_RECIPIENT for this APP_ENV, if anything. */
    static Optional<String> mailWarning(AppProperties app, MailProperties mail) {
        if (!mail.enabled()) {
            return Optional.empty();
        }
        if (!app.isProduction() && mail.testRecipient() == null) {
            return Optional.of(
                    "MAIL_TEST_RECIPIENT is empty: outside production no e-mail is sent until it is set in deploy/.env.");
        }
        if (app.isProduction() && mail.testRecipient() != null) {
            return Optional.of("MAIL_TEST_RECIPIENT is set while APP_ENV=prod: every e-mail goes only to that address, "
                    + "not to the rule's recipients. Empty it in deploy/.env to e-mail the recipients.");
        }
        return Optional.empty();
    }
}
