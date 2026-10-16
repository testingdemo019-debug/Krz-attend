package com.krztechnologies.attend.common.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-wide settings from deploy/.env (APP_*, ORG_*, BOOTSTRAP_ADMIN_EMAILS, DATA_DIR, SESSION_*).
 *
 * @param env dev or prod
 * @param baseUrl public URL, used only for absolute links in e-mails
 * @param timezone default for Settings → General; all day logic and display
 * @param bootstrapAdminEmails work e-mails that become System Administrator while no administrator exists
 * @param dataDir the data folder (containers: /data)
 * @param sessionAbsoluteHours absolute session lifetime
 * @param helpdeskEmail IT's address for "Trouble signing in?" (IT_HELPDESK_EMAIL), null when not configured
 */
@Validated
@ConfigurationProperties("krz.app")
public record AppProperties(
        @NotNull Environment env,
        @NotBlank String version,
        @NotBlank String baseUrl,
        @NotNull ZoneId timezone,
        @NotBlank String orgName,
        @NotBlank String orgDomain,
        List<String> bootstrapAdminEmails,
        @NotBlank String dataDir,
        int sessionAbsoluteHours,
        @Nullable String helpdeskEmail) {

    public AppProperties {
        helpdeskEmail = helpdeskEmail == null || helpdeskEmail.isBlank() ? null : helpdeskEmail.trim();
        bootstrapAdminEmails = bootstrapAdminEmails == null
                ? List.of()
                : bootstrapAdminEmails.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    /** The data folder as a path (relative paths resolve against the working directory). */
    public Path dataPath() {
        return Path.of(dataDir);
    }

    public boolean isProduction() {
        return env == Environment.PROD;
    }

    /** APP_ENV values. */
    public enum Environment {
        DEV,
        PROD
    }
}
