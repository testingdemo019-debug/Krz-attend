package com.krztechnologies.attend.directory.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.graph.EntraProperties;
import com.krztechnologies.attend.common.graph.GraphClient;
import com.krztechnologies.attend.common.graph.GraphException;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.directory.domain.DirectorySyncRun;
import com.krztechnologies.attend.directory.domain.DirectorySyncState;
import com.krztechnologies.attend.directory.repository.DirectorySyncRunRepository;
import com.krztechnologies.attend.directory.repository.DirectorySyncStateRepository;
import com.krztechnologies.attend.directory.repository.UnsyncedAccountStore;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeIdentityService;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Directory sync (R16) with Microsoft Graph {@code users/delta}: incremental with the stored delta link, a full
 * re-sync weekly. New users get an identity only; disabled or removed users are disabled with their consequences;
 * re-enabled users are enabled again. Each run is stored as SY-n and audited; a failure raises NR-11.
 */
@Service
public class DirectorySyncService {

    private static final Logger LOG = LoggerFactory.getLogger(DirectorySyncService.class);
    private static final Duration FULL_SYNC_EVERY = Duration.ofDays(7);
    private static final String FULL_URL = "/users/delta?$select=" + DirectoryScope.SELECT;

    private final GraphClient graph;
    private final DirectoryScope scope;
    private final DirectoryLookupService lookup;
    private final EmployeeIdentityService identities;
    private final EmployeeDirectory directory;
    private final DirectorySyncRunRepository runs;
    private final DirectorySyncStateRepository states;
    private final UnsyncedAccountStore unsynced;
    private final NotificationService notifications;
    private final AuditService audit;
    private final SettingsService settings;
    private final EntraProperties entra;
    private final IdGenerator ids;
    private final TimeService time;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final ReentrantLock running = new ReentrantLock();

    public DirectorySyncService(
            GraphClient graph,
            DirectoryScope scope,
            DirectoryLookupService lookup,
            EmployeeIdentityService identities,
            EmployeeDirectory directory,
            DirectorySyncRunRepository runs,
            DirectorySyncStateRepository states,
            UnsyncedAccountStore unsynced,
            NotificationService notifications,
            AuditService audit,
            SettingsService settings,
            EntraProperties entra,
            IdGenerator ids,
            TimeService time,
            TransactionTemplate transactions,
            JsonMapper json) {
        this.graph = graph;
        this.scope = scope;
        this.lookup = lookup;
        this.identities = identities;
        this.directory = directory;
        this.runs = runs;
        this.states = states;
        this.unsynced = unsynced;
        this.notifications = notifications;
        this.audit = audit;
        this.settings = settings;
        this.entra = entra;
        this.ids = ids;
        this.time = time;
        this.transactions = transactions;
        this.json = json;
    }

    /** A sync run for Settings → Microsoft sign-in → Sync history. */
    public record SyncRunView(
            String code,
            Instant startedAt,
            @Nullable Instant finishedAt,
            String trigger,
            @Nullable String triggeredBy,
            boolean fullSync,
            int added,
            int updated,
            int disabled,
            int total,
            String status,
            List<String> warnings,
            @Nullable String error,
            @Nullable Integer durationMs) {}

    /** Runs a sync now; 409 SYNC_RUNNING if one is already running. */
    public SyncRunView run(DirectorySyncRun.Trigger trigger, AuditActor actor) {
        if (!running.tryLock()) {
            throw new Errors.Conflict("SYNC_RUNNING", "A directory sync is already running.");
        }
        try {
            return doRun(trigger, actor);
        } finally {
            running.unlock();
        }
    }

    /** Whether the scheduled job should run now (every {@code syncEvery} hours). */
    @Transactional(readOnly = true)
    public boolean isDue() {
        Duration every = Duration.ofHours(settings.signIn().syncEvery());
        return runs.findFirstByOrderByStartedAtDesc()
                .map(last -> !last.getStartedAt().plus(every).isAfter(time.now()))
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public List<SyncRunView> history() {
        List<DirectorySyncRun> found = runs.findTop50ByOrderByStartedAtDesc();
        var people = directory.summaries(found.stream()
                .map(DirectorySyncRun::getTriggeredBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return found.stream().map(r -> toView(r, people)).toList();
    }

    /**
     * An account in the tenant that is outside the sync-scope groups (Settings → Users &amp; roles).
     *
     * @param email the work e-mail; only for viewers with people.view.sensitive, otherwise null
     */
    public record UnsyncedAccount(String name, @Nullable String email) {}

    /**
     * @param accounts the enabled members outside the sync-scope groups, as the latest syncs saw them, by name
     * @param groups the names of the sync-scope groups to add them to
     */
    public record Unsynced(List<UnsyncedAccount> accounts, List<String> groups) {}

    @Transactional(readOnly = true)
    public Unsynced unsynced(boolean sensitive) {
        List<UnsyncedAccount> accounts = unsynced.all().stream()
                .map(a -> new UnsyncedAccount(a.displayName(), sensitive ? a.upn() : null))
                .toList();
        return new Unsynced(
                accounts,
                accounts.isEmpty()
                        ? List.of()
                        : lookup.syncGroups().stream()
                                .map(DirectoryLookupService.SyncGroup::name)
                                .toList());
    }

    @Transactional(readOnly = true)
    public Optional<SyncRunView> latest() {
        return runs.findFirstByOrderByStartedAtDesc().map(this::toView);
    }

    private SyncRunView doRun(DirectorySyncRun.Trigger trigger, AuditActor actor) {
        DirectorySyncRun run = transactions.execute(s ->
                runs.save(new DirectorySyncRun(ids.next(CodeType.SYNC_RUN), time.now(), trigger, actor.employeeId())));
        Counters c = new Counters();
        boolean full = false;
        DirectorySyncRun.Status status;
        String error = null;
        try {
            DirectorySyncState state =
                    states.findById(DirectorySyncState.SINGLETON_ID).orElseThrow();
            full = state.getDeltaLink() == null
                    || state.getLastFullSyncAt() == null
                    || state.getLastFullSyncAt().plus(FULL_SYNC_EVERY).isBefore(time.now());
            Set<String> groups = entra.syncScope() == EntraProperties.SyncScope.GROUPS ? lookup.groupMembers() : null;
            Set<UUID> seen = new HashSet<>();
            int eraseAfterDays = settings.signIn().eraseAfterDays();
            String url = full ? FULL_URL : state.getDeltaLink();
            String deltaLink = null;
            while (url != null) {
                JsonNode page = graph.get(url);
                for (JsonNode user : page.path("value")) {
                    process(user, groups, eraseAfterDays, seen, c);
                }
                if (page.hasNonNull("@odata.nextLink")) {
                    url = page.path("@odata.nextLink").asString();
                } else {
                    deltaLink = page.path("@odata.deltaLink").asString(null);
                    url = null;
                }
            }
            if (full) {
                unsynced.forgetNotSeenSince(run.getStartedAt());
                for (UUID objectId : identities.enabledObjectIds(entra.tenantId())) {
                    if (!seen.contains(objectId)
                            && identities.applyRemoval(entra.tenantId(), objectId, eraseAfterDays)) {
                        c.disabled++;
                    }
                }
            }
            boolean fullRun = full;
            String link = deltaLink;
            transactions.executeWithoutResult(s -> states.findById(DirectorySyncState.SINGLETON_ID)
                    .orElseThrow()
                    .completed(link, fullRun, time.now()));
            status = c.warnings.isEmpty() ? DirectorySyncRun.Status.SUCCESS : DirectorySyncRun.Status.WARNING;
        } catch (GraphException e) {
            status = DirectorySyncRun.Status.FAILED;
            error = "Microsoft Graph error " + e.code() + (e.status() > 0 ? " (HTTP " + e.status() + ")" : "");
            LOG.warn("Directory sync {} failed: {}", run.getCode(), error);
        } catch (RuntimeException e) {
            status = DirectorySyncRun.Status.FAILED;
            error = "Unexpected error; see the server log (trace ID).";
            LOG.error("Directory sync {} failed", run.getCode(), e);
        }
        return finish(run, full, c, status, error, actor);
    }

    private void process(JsonNode user, Set<String> groups, int eraseAfterDays, Set<UUID> seen, Counters c) {
        UUID objectId = UUID.fromString(user.path("id").asString());
        if (user.has("@removed")) {
            unsynced.forget(objectId);
            if (identities.applyRemoval(entra.tenantId(), objectId, eraseAfterDays)) {
                c.disabled++;
            }
            return;
        }
        JsonNode complete = DirectoryScope.isComplete(user)
                ? user
                : graph.get("/users/" + objectId + "?$select=" + DirectoryScope.SELECT);
        var exclusion = scope.exclusion(complete, groups);
        if (exclusion.isPresent()) {
            if (identities.applyRemoval(entra.tenantId(), objectId, eraseAfterDays)) {
                c.disabled++;
            }
            if (exclusion.get() == DirectoryScope.Exclusion.NOT_IN_GROUP
                    && complete.path("accountEnabled").asBoolean(false)) {
                String upn = complete.path("userPrincipalName").asString();
                unsynced.outsideScope(objectId, complete.path("displayName").asString(upn), upn, time.now());
                c.warnings.add(upn + " is in the tenant but not in a sync-scope group");
            } else {
                unsynced.forget(objectId);
            }
            return;
        }
        unsynced.forget(objectId);
        seen.add(objectId);
        switch (identities.applyDirectoryEntry(entra.tenantId(), DirectoryScope.toEntry(complete), eraseAfterDays)) {
            case ADDED -> c.added++;
            case UPDATED -> c.updated++;
            case DISABLED -> c.disabled++;
            case UNCHANGED -> {}
        }
    }

    private SyncRunView finish(
            DirectorySyncRun run,
            boolean full,
            Counters c,
            DirectorySyncRun.Status status,
            String error,
            AuditActor actor) {
        DirectorySyncRun done = transactions.execute(s -> {
            DirectorySyncRun managed = runs.findById(run.getId()).orElseThrow();
            managed.finish(
                    time.now(),
                    full,
                    c.added,
                    c.updated,
                    c.disabled,
                    (int) identities.countEnabled(),
                    json.writeValueAsString(c.warnings),
                    status,
                    error);
            audit.record(
                    actor,
                    AuditCategory.EMPLOYEES,
                    managed.getTrigger() == DirectorySyncRun.Trigger.MANUAL
                            ? "Directory sync (manual)"
                            : "Directory sync",
                    managed.getCode(),
                    null,
                    status + ": " + c.added + " added, " + c.updated + " updated, " + c.disabled + " disabled",
                    error == null ? null : Map.of("error", error));
            if (status == DirectorySyncRun.Status.FAILED) {
                notifications.publish(
                        "NR-11",
                        new NotificationRequest(
                                "Directory sync failed",
                                error,
                                "/settings/signin",
                                "NR-11:" + managed.getCode(),
                                Map.of("runCode", managed.getCode())));
            }
            return managed;
        });
        LOG.info(
                "Directory sync {}: {} ({} added, {} updated, {} disabled)",
                done.getCode(),
                status,
                c.added,
                c.updated,
                c.disabled);
        return toView(done);
    }

    /** One run, with the name of the person who started it. */
    private SyncRunView toView(DirectorySyncRun run) {
        List<UUID> by = run.getTriggeredBy() == null ? List.of() : List.of(run.getTriggeredBy());
        return toView(run, directory.summaries(by));
    }

    private SyncRunView toView(DirectorySyncRun r, Map<UUID, EmployeeDirectory.EmployeeSummary> people) {
        var by = r.getTriggeredBy() == null ? null : people.get(r.getTriggeredBy());
        List<String> warnings = new ArrayList<>();
        json.readTree(r.getWarnings()).forEach(w -> warnings.add(w.asString()));
        return new SyncRunView(
                r.getCode(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.getTrigger().name(),
                r.getTrigger() == DirectorySyncRun.Trigger.SCHEDULE ? "Schedule" : by == null ? null : by.displayName(),
                r.isFullSync(),
                r.getAdded(),
                r.getUpdated(),
                r.getDisabled(),
                r.getTotal(),
                r.getStatus() == null ? null : r.getStatus().name(),
                warnings,
                r.getError(),
                r.getDurationMs());
    }

    private static final class Counters {
        private int added;
        private int updated;
        private int disabled;
        private final List<String> warnings = new ArrayList<>();
    }
}
