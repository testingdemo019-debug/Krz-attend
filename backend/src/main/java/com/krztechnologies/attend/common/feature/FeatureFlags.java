package com.krztechnologies.attend.common.feature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Which integrations are on, from the environment. Shown in {@code /meta} and Settings → System. Push and HRMS are
 * off until their PENDING items are resolved (P-06, P-07).
 */
@Component
public class FeatureFlags {

    private final boolean mailEnabled;
    private final boolean pushEnabled;
    private final boolean hrmsEnabled;
    private final boolean devTools;

    public FeatureFlags(
            @Value("${krz.mail.enabled}") boolean mailEnabled,
            @Value("${krz.push.enabled:false}") boolean pushEnabled,
            @Value("${krz.hrms.enabled:false}") boolean hrmsEnabled,
            Environment environment) {
        this.mailEnabled = mailEnabled;
        this.pushEnabled = pushEnabled;
        this.hrmsEnabled = hrmsEnabled;
        this.devTools = environment.acceptsProfiles(Profiles.of("dev"));
    }

    public boolean mailEnabled() {
        return mailEnabled;
    }

    public boolean pushEnabled() {
        return pushEnabled;
    }

    public boolean hrmsEnabled() {
        return hrmsEnabled;
    }

    public boolean devTools() {
        return devTools;
    }
}
