package com.krztechnologies.attend.directory.service;

import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.graph.GraphClient;
import com.krztechnologies.attend.common.graph.GraphException;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.settings.SignInSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Microsoft sign-in: the connection facts are read-only (from deploy/.env; the secret is never shown);
 * idle timeout, sync interval and erase-after days are editable; "Test connection" acquires an app token and reads one
 * user from Graph.
 */
@Service
public class SignInSettingsService {

    /** The audit action of "Test connection" (its last entry is the page's "Last test"). */
    private static final String TESTED = "Tested Microsoft sign-in";

    private final EntraProperties entra;
    private final AppProperties app;
    private final SettingsService settings;
    private final DirectorySyncService sync;
    private final DirectoryLookupService lookup;
    private final GraphClient graph;
    private final AuditService audit;
    private final boolean secretSet;

    public SignInSettingsService(
            EntraProperties entra,
            AppProperties app,
            SettingsService settings,
            DirectorySyncService sync,
            DirectoryLookupService lookup,
            GraphClient graph,
            AuditService audit,
            @Value("${krz.entra.client-secret:}") String clientSecret) {
        this.entra = entra;
        this.app = app;
        this.settings = settings;
        this.sync = sync;
        this.lookup = lookup;
        this.graph = graph;
        this.audit = audit;
        this.secretSet = clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * @param secretSet whether ENTRA_CLIENT_SECRET is present (the value is never returned)
     * @param redirectUri the redirect URI to register in Entra for APP_BASE_URL
     * @param syncScope ENTRA_SYNC_SCOPE: {@code ALL} members of the tenant, or the members of {@code GROUPS}
     * @param syncGroups ENTRA_SYNC_GROUP_IDS with the groups' names: the groups synced with scope GROUPS (set on the
     *     server, read-only here)
     */
    public record SignInView(
            UUID tenantId,
            String clientId,
            String redirectUri,
            String allowedDomain,
            boolean secretSet,
            @Nullable LocalDate secretExpires,
            String syncScope,
            List<DirectoryLookupService.SyncGroup> syncGroups,
            @Nullable Instant lastTestedAt,
            SignInSettings settings,
            int version,
            DirectorySyncService.@Nullable SyncRunView lastSync) {}

    /**
     * @param step the step that failed: {@code token} or {@code graph}; null when it worked
     * @param code Microsoft's error code (e.g. {@code invalid_client}, {@code Authorization_RequestDenied})
     */
    public record TestResult(
            boolean ok, @Nullable String step, @Nullable String code, String message) {}

    @Transactional(readOnly = true)
    public SignInView view() {
        return view(settings.get(SettingsArea.SIGN_IN));
    }

    /** Saves the editable values; the answer shows what was saved (the cache refreshes only after commit). */
    @Transactional
    public SignInView update(SignInSettings values, int version) {
        return view(settings.update(SettingsArea.SIGN_IN, values, version));
    }

    private SignInView view(SettingsService.Versioned<SignInSettings> current) {
        return new SignInView(
                entra.tenantId(),
                entra.clientId(),
                app.baseUrl().replaceAll("/$", "") + "/login/oauth2/code/azure",
                entra.allowedDomain(),
                secretSet,
                entra.clientSecretExpires(),
                entra.syncScope().name(),
                lookup.syncGroups(),
                audit.lastRecorded(AuditCategory.SETTINGS, TESTED).orElse(null),
                current.value(),
                current.version(),
                sync.latest().orElse(null));
    }

    @Transactional
    public TestResult test() {
        TestResult result;
        try {
            graph.verifyToken();
            try {
                graph.get("/users?$top=1&$select=id");
                result = new TestResult(true, null, null, "Connected: token issued and the directory can be read.");
            } catch (GraphException e) {
                result = new TestResult(
                        false,
                        "graph",
                        e.code(),
                        "The token works, but reading users failed (HTTP " + e.status() + ").");
            }
        } catch (GraphException e) {
            result = new TestResult(
                    false,
                    "token",
                    e.code(),
                    "Microsoft refused the app credentials (tenant ID, client ID or secret).");
        }
        audit.record(
                AuditCategory.SETTINGS,
                TESTED,
                "Microsoft Entra ID",
                null,
                result.ok() ? "Connected" : "Failed: " + result.code(),
                null);
        return result;
    }
}
