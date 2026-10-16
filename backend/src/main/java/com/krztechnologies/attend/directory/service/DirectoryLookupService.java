package com.krztechnologies.attend.directory.service;

import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.graph.GraphClient;
import com.krztechnologies.attend.common.graph.GraphException;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Just-in-time lookup of one user in Microsoft Graph (a sign-in before the first sync), with the sync's scope rules. */
@Service
public class DirectoryLookupService {

    /** Group names change rarely; they are read again after this long. */
    private static final Duration GROUP_NAMES_FOR = Duration.ofMinutes(10);

    private final GraphClient graph;
    private final DirectoryScope scope;
    private final EntraProperties entra;
    private final TimeService time;
    private volatile CachedGroups groups;

    public DirectoryLookupService(GraphClient graph, DirectoryScope scope, EntraProperties entra, TimeService time) {
        this.graph = graph;
        this.scope = scope;
        this.entra = entra;
        this.time = time;
    }

    /** A sync-scope group: its object ID and display name (the ID when the name cannot be read). */
    public record SyncGroup(String id, String name) {}

    private record CachedGroups(List<SyncGroup> groups, Instant at) {}

    /**
     * The sync-scope groups (ENTRA_SYNC_GROUP_IDS) with their names from Graph, kept for 10 minutes; empty with
     * ENTRA_SYNC_SCOPE=all. Reading a group's name needs the same GroupMember.Read.All as reading its members.
     */
    public List<SyncGroup> syncGroups() {
        if (entra.syncScope() != EntraProperties.SyncScope.GROUPS) {
            return List.of();
        }
        Instant now = time.now();
        CachedGroups hit = groups;
        if (hit != null && now.isBefore(hit.at().plus(GROUP_NAMES_FOR))) {
            return hit.groups();
        }
        List<SyncGroup> named = entra.syncGroupIds().stream()
                .map(id -> {
                    try {
                        return new SyncGroup(
                                id,
                                graph.get("/groups/" + id + "?$select=displayName")
                                        .path("displayName")
                                        .asString(id));
                    } catch (GraphException e) {
                        return new SyncGroup(id, id);
                    }
                })
                .toList();
        groups = new CachedGroups(named, now);
        return named;
    }

    /** The outcome of a lookup. */
    public sealed interface Lookup {
        /** In scope: the identity to create. */
        record Found(EmployeeIdentityService.DirectoryEntry entry) implements Lookup {}

        /** Known to Graph but not synced (guest, other domain, excluded, not in a sync group). */
        record OutOfScope(DirectoryScope.Exclusion reason) implements Lookup {}

        /** Not in the directory. */
        record NotFound() implements Lookup {}
    }

    /** Throws {@link GraphException} when Graph cannot be asked (no credentials, unreachable). */
    public Lookup lookup(UUID objectId) {
        JsonNode user;
        try {
            user = graph.get("/users/" + objectId + "?$select=" + DirectoryScope.SELECT);
        } catch (GraphException e) {
            if (e.status() == 404) {
                return new Lookup.NotFound();
            }
            throw e;
        }
        Set<String> groups = entra.syncScope() == EntraProperties.SyncScope.GROUPS ? groupMembers() : null;
        return scope.exclusion(user, groups)
                .<Lookup>map(Lookup.OutOfScope::new)
                .orElseGet(() -> new Lookup.Found(DirectoryScope.toEntry(user)));
    }

    /** Object IDs of every (transitive) user member of the sync groups. Needs GroupMember.Read.All (PENDING P-15). */
    Set<String> groupMembers() {
        Set<String> members = new HashSet<>();
        for (String groupId : entra.syncGroupIds()) {
            String url = "/groups/" + groupId + "/transitiveMembers/microsoft.graph.user?$select=id&$top=999";
            while (url != null) {
                JsonNode page = graph.get(url);
                page.path("value").forEach(m -> members.add(m.path("id").asString()));
                url = page.hasNonNull("@odata.nextLink")
                        ? page.path("@odata.nextLink").asString()
                        : null;
            }
        }
        return members;
    }
}
