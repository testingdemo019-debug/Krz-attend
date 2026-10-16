package com.krztechnologies.attend.auth.web;

import com.krztechnologies.attend.access.service.AccessRequestService;
import com.krztechnologies.attend.access.service.AccessViews;
import com.krztechnologies.attend.auth.domain.SessionKeys;
import com.krztechnologies.attend.auth.service.MeService;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.PublicEndpoint;
import com.krztechnologies.attend.common.security.SignedIn;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.system.service.LogoService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user ({@code /me}), the app's runtime configuration ({@code /meta}) and public branding. */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    private static final String APP_NAME = "KRZ Attend";

    private final MeService me;
    private final AccessRequestService requests;
    private final CurrentUser currentUser;
    private final SettingsService settings;
    private final FeatureFlags features;
    private final AppProperties app;
    private final LogoService logo;
    private final EntraProperties entra;

    public MeController(
            MeService me,
            AccessRequestService requests,
            CurrentUser currentUser,
            SettingsService settings,
            FeatureFlags features,
            AppProperties app,
            LogoService logo,
            EntraProperties entra) {
        this.me = me;
        this.requests = requests;
        this.currentUser = currentUser;
        this.settings = settings;
        this.features = features;
        this.app = app;
        this.logo = logo;
        this.entra = entra;
    }

    public record AccessRequestBody(@Size(max = 500) String note) {}

    /** Integrations as the UI shows them. */
    public record Features(boolean mail, boolean push, boolean hrms) {}

    /**
     * The attendance rules the Events page's "How punch rules work" explains.
     *
     * @param pushMode {@code FINAL} or {@code EVERY}
     */
    public record PunchRules(int duplicateMinutes, int turnedBackSeconds, String pushMode) {}

    /**
     * @param logoUrl null until a logo is uploaded (the UI then shows the "KRZ" text mark)
     * @param unknownFaceHours how long crops of unknown faces are kept (privacy.unknownHours; the Review Queue's note)
     * @param punchRules the rule values the Events page's help names
     * @param employeeIdPattern the HRMS Employee ID format (general.employeeIdPattern; the employment editor's check)
     */
    public record Meta(
            String appVersion,
            String timezone,
            String dateFormat,
            String timeFormat,
            String orgName,
            @Nullable String logoUrl,
            Features features,
            int idleTimeoutMin,
            int unknownFaceHours,
            PunchRules punchRules,
            String employeeIdPattern) {}

    /**
     * The sign-in screen's organisation facts (public).
     *
     * @param allowedDomain the work e-mail domain (the e-mail field's hint and first check)
     * @param helpdeskEmail "Trouble signing in? Contact IT (…)", null when IT_HELPDESK_EMAIL is not set
     */
    public record Branding(
            String orgName,
            String appName,
            String appVersion,
            String allowedDomain,
            @Nullable String logoUrl,
            @Nullable String helpdeskEmail) {}

    @Operation(summary = "The signed-in user: identity, access state, roles, permissions, session")
    @SignedIn(requireAccess = false)
    @GetMapping("/me")
    public MeService.Me get(HttpSession session) {
        return me.me(
                currentUser.employeeId(),
                instant(session, SessionKeys.SIGNED_IN_AT),
                instant(session, SessionKeys.LAST_USER_ACTIVITY));
    }

    @Operation(summary = "Ask for access (only without a role; at most 3 a day; NR-06)")
    @SignedIn(requireAccess = false)
    @PostMapping("/me/access-request")
    public AccessViews.AccessRequestView requestAccess(@Valid @RequestBody AccessRequestBody body) {
        return requests.submit(body.note());
    }

    @Operation(summary = "Keep the session alive (\"Stay signed in\"); refreshes the idle timer")
    @SignedIn(requireAccess = false)
    @PostMapping("/me/keepalive")
    public MeService.SessionInfo keepalive(HttpSession session) {
        return get(session).session();
    }

    @Operation(summary = "Runtime configuration for the frontend (time zone, formats, features)")
    @SignedIn(requireAccess = false)
    @GetMapping("/meta")
    public Meta meta() {
        var general = settings.general();
        var rules = settings.attendanceRules();
        return new Meta(
                app.version(),
                general.timezone().getId(),
                general.dateFormat(),
                general.timeFormat(),
                general.orgName(),
                logo.url(),
                new Features(features.mailEnabled(), features.pushEnabled(), features.hrmsEnabled()),
                settings.signIn().idleTimeoutMin(),
                settings.privacy().unknownHours(),
                new PunchRules(
                        rules.duplicateMinutes(),
                        rules.turnedBackSeconds(),
                        rules.pushMode().name()),
                general.employeeIdPattern());
    }

    @Operation(summary = "Organisation branding for the sign-in screen")
    @PublicEndpoint
    @GetMapping("/public/branding")
    public Branding branding() {
        return new Branding(
                settings.general().orgName(),
                APP_NAME,
                app.version(),
                entra.allowedDomain(),
                logo.url(),
                app.helpdeskEmail());
    }

    private static Instant instant(HttpSession session, String key) {
        Object value = session.getAttribute(key);
        return value instanceof Instant instant ? instant : Instant.EPOCH;
    }
}
