package com.krztechnologies.attend.directory.service;

import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import com.krztechnologies.attend.notification.service.MailProperties;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Who is synced (R16): member accounts (never guests) whose UPN is in ENTRA_ALLOWED_DOMAIN, minus ENTRA_SYNC_EXCLUDE
 * and the noreply sender mailbox; with ENTRA_SYNC_SCOPE=groups, only members of ENTRA_SYNC_GROUP_IDS.
 */
@Component
public class DirectoryScope {

    /** The Graph user properties every sync and lookup selects. */
    public static final String SELECT =
            "id,displayName,givenName,surname,userPrincipalName,mail,accountEnabled,userType";

    private final EntraProperties entra;
    private final String senderMailbox;

    public DirectoryScope(EntraProperties entra, MailProperties mail) {
        this.entra = entra;
        this.senderMailbox = mail.sender().toLowerCase(Locale.ROOT);
    }

    /** Why a Graph user is not synced. */
    public enum Exclusion {
        GUEST,
        WRONG_DOMAIN,
        EXCLUDED,
        NOT_IN_GROUP
    }

    /** True when the Graph object has every property {@link #SELECT} asks for (delta updates may carry fewer). */
    public static boolean isComplete(JsonNode user) {
        return user.hasNonNull("displayName")
                && user.hasNonNull("userPrincipalName")
                && user.has("accountEnabled")
                && user.hasNonNull("userType");
    }

    /**
     * @param groupMembers object IDs of the sync groups (scope=groups), or null for scope=all
     */
    public Optional<Exclusion> exclusion(JsonNode user, Set<String> groupMembers) {
        String upn = user.path("userPrincipalName").asString("").toLowerCase(Locale.ROOT);
        if (!"Member".equalsIgnoreCase(user.path("userType").asString("")) || upn.contains("#ext#")) {
            return Optional.of(Exclusion.GUEST);
        }
        if (!upn.endsWith("@" + entra.allowedDomain())) {
            return Optional.of(Exclusion.WRONG_DOMAIN);
        }
        if (entra.syncExclude().contains(upn) || upn.equals(senderMailbox)) {
            return Optional.of(Exclusion.EXCLUDED);
        }
        if (groupMembers != null && !groupMembers.contains(user.path("id").asString(""))) {
            return Optional.of(Exclusion.NOT_IN_GROUP);
        }
        return Optional.empty();
    }

    public static EmployeeIdentityService.DirectoryEntry toEntry(JsonNode user) {
        return new EmployeeIdentityService.DirectoryEntry(
                UUID.fromString(user.path("id").asString()),
                user.path("displayName").asString(),
                text(user, "givenName"),
                text(user, "surname"),
                user.path("userPrincipalName").asString(),
                text(user, "mail"),
                user.path("accountEnabled").asBoolean(false));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asString("").isBlank() ? null : value.asString();
    }
}
