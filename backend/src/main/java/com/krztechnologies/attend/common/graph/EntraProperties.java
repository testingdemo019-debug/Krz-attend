package com.krztechnologies.attend.common.graph;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Microsoft Entra ID and Microsoft Graph (ENTRA_* in deploy/.env). The client secret is bound separately and never
 * exposed through this record.
 *
 * @param clientSecretExpires expiry of the client secret (drives NR-09); null when not entered yet
 * @param syncExclude UPNs that are never synced (service and shared mailboxes)
 * @param loginBaseUrl https://login.microsoftonline.com (overridable for tests)
 * @param graphBaseUrl https://graph.microsoft.com/v1.0 (overridable for tests)
 */
@Validated
@ConfigurationProperties("krz.entra")
public record EntraProperties(
        @NotNull UUID tenantId,
        @NotBlank String clientId,
        LocalDate clientSecretExpires,
        @NotBlank String allowedDomain,
        @NotNull SyncScope syncScope,
        List<String> syncGroupIds,
        List<String> syncExclude,
        @NotNull URI loginBaseUrl,
        @NotNull URI graphBaseUrl) {

    public EntraProperties {
        allowedDomain = allowedDomain == null ? null : allowedDomain.trim().toLowerCase(Locale.ROOT);
        syncGroupIds = clean(syncGroupIds);
        syncExclude =
                clean(syncExclude).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private static List<String> clean(List<String> values) {
        return values == null
                ? List.of()
                : values.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** The single-tenant issuer validated on every sign-in. */
    public String issuerUri() {
        return loginBaseUrl + "/" + tenantId + "/v2.0";
    }

    public String authorizationUri() {
        return loginBaseUrl + "/" + tenantId + "/oauth2/v2.0/authorize";
    }

    public String tokenUri() {
        return loginBaseUrl + "/" + tenantId + "/oauth2/v2.0/token";
    }

    public String jwkSetUri() {
        return loginBaseUrl + "/" + tenantId + "/discovery/v2.0/keys";
    }

    /** ENTRA_SYNC_SCOPE values. */
    public enum SyncScope {
        ALL,
        GROUPS
    }
}
