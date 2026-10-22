package com.krztechnologies.attend.review.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.realtime.SseHub;
import com.krztechnologies.attend.common.redis.ConfigPublisher;
import com.krztechnologies.attend.common.redis.ConfigSignal;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.common.undo.UndoTokens;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.crossing.service.CrossingDecisions;
import com.krztechnologies.attend.crossing.service.CrossingDecisions.Sighting;
import com.krztechnologies.attend.crossing.service.FaceQuality;
import com.krztechnologies.attend.crossing.service.ReviewQueueChanged;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.enrolment.service.TemplateService;
import com.krztechnologies.attend.notification.service.RuleSettings;
import com.krztechnologies.attend.recognition.service.ActiveThresholds;
import com.krztechnologies.attend.recognition.service.ConfidenceBands;
import com.krztechnologies.attend.recognition.service.RecognitionService;
import com.krztechnologies.attend.review.repository.ReviewStore;
import com.krztechnologies.attend.rules.service.Decision;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Review Queue (R12): items with their crossings, candidates and template eligibility; decisions one by one or in
 * bulk, each audited and recomputing the affected days through the crossings; undo within the undo window (the
 * snapshot is kept on the item); a template from the best crop when the employee has consent and the crop is good
 * enough; {@code reid.bind}/{@code reid.unbind} for back-view exits.
 */
@Service
public class ReviewService {

    /** The item types and the prototype's short labels. */
    static final Map<String, String> TYPE_LABELS = Map.of(
            "UNKNOWN", "Unknown face",
            "UNCERTAIN", "Uncertain match",
            "REID", "Back-view exit",
            "LIVENESS", "Liveness alert");

    private static final String UNDO_KIND = "review";
    private static final int NR03_DEFAULT_MINUTES = 30;
    private static final int MAX_OPEN_LISTED = 2_000;
    private static final int MAX_EXPORTED = 50_000;
    /** The punch effects a decision's toast names. */
    private static final Set<String> PUNCHES = Set.of("CHECK_IN", "CHECK_OUT", "MOVEMENT");

    private static final Set<String> ASSIGN_REASONS = Set.of("Correct identity", "Wrong suggestion", "Other");
    private static final Set<String> NOT_EMPLOYEE_REASONS = Set.of("Visitor", "Wrong suggestion", "Other");

    /** The decisions (R12) and the prototype's labels. */
    public enum Action {
        CONFIRM("Confirmed"),
        ASSIGN("Assigned to employee"),
        NOT_EMPLOYEE("Not an employee"),
        REJECT("Rejected — bad image"),
        SPOOF("Spoof confirmed"),
        FALSE_ALARM("False alarm — confirmed");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        boolean identifies() {
            return this == CONFIRM || this == ASSIGN || this == FALSE_ALARM;
        }
    }

    /** Which decisions each item type allows (the prototype's buttons). */
    private static final Map<String, Set<Action>> ALLOWED = Map.of(
            "UNKNOWN", Set.of(Action.ASSIGN, Action.NOT_EMPLOYEE, Action.REJECT),
            "UNCERTAIN", Set.of(Action.CONFIRM, Action.ASSIGN, Action.NOT_EMPLOYEE, Action.REJECT),
            "REID", Set.of(Action.CONFIRM, Action.ASSIGN, Action.REJECT),
            "LIVENESS", Set.of(Action.SPOOF, Action.FALSE_ALARM, Action.REJECT));

    private final ReviewStore store;
    private final CrossingDecisions crossings;
    private final EmployeeDirectory employees;
    private final RecognitionService recognition;
    private final TemplateService templates;
    private final UndoTokens undo;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final SettingsService settings;
    private final RuleSettings rules;
    private final ConfigPublisher config;
    private final SseHub realtime;
    private final TimeService time;
    private final JsonMapper json;

    public ReviewService(
            ReviewStore store,
            CrossingDecisions crossings,
            EmployeeDirectory employees,
            RecognitionService recognition,
            TemplateService templates,
            UndoTokens undo,
            AuditService audit,
            CurrentUser currentUser,
            SettingsService settings,
            RuleSettings rules,
            ConfigPublisher config,
            SseHub realtime,
            TimeService time,
            JsonMapper json) {
        this.store = store;
        this.crossings = crossings;
        this.employees = employees;
        this.recognition = recognition;
        this.templates = templates;
        this.undo = undo;
        this.audit = audit;
        this.currentUser = currentUser;
        this.settings = settings;
        this.rules = rules;
        this.config = config;
        this.realtime = realtime;
        this.time = time;
        this.json = json;
    }

    /**
     * A decision on one item.
     *
     * @param employeeId ASSIGN (required), FALSE_ALARM (someone other than the top candidate)
     * @param reason ASSIGN and NOT_EMPLOYEE overriding a suggestion need one; "Other" needs a note
     * @param sightingCode the crossing whose crop becomes the template (an unknown face's best sighting)
     */
    public record DecisionInput(
            Action action, UUID employeeId, String reason, String note, boolean addTemplate, String sightingCode) {}

    /**
     * The queue filters (all optional; null or empty lists = any).
     *
     * @param status {@code OPEN} (default), {@code RESOLVED}, {@code EXPIRED} or {@code CLOSED} (the Resolved tab)
     * @param actions resolutions (items no longer open)
     * @param resolvedBy reviewers' employee ids, or {@code SYSTEM}
     * @param direction open items with a crossing in this direction
     * @param band open items whose top candidate has this confidence band
     * @param waiting only open items waiting longer than the NR-03 threshold
     */
    public record Filter(
            List<String> types,
            String status,
            LocalDate from,
            LocalDate to,
            String q,
            List<String> actions,
            List<String> resolvedBy,
            String direction,
            String band,
            boolean waiting) {}

    /** What an undo snapshot keeps: the crossings as they were before the decision. */
    private record Snapshot(List<Sighting> crossings) {}

    /**
     * Open items oldest first, or the others sorted.
     *
     * @param sort items no longer open: {@code resolvedAt} (default, newest first), {@code code}, {@code seen},
     *     {@code resolution}, {@code resolvedBy}
     */
    @Transactional(readOnly = true)
    public PageResponse<ReviewViews.Item> list(Filter f, Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(
                page, size, sort, ReviewStore.SORTS, Sort.by(Sort.Direction.DESC, ReviewStore.SORTS.get("resolvedAt")));
        Sort.Order order = pageable.getSort().iterator().next();
        String status = f.status() == null ? "OPEN" : f.status();
        var filter = storeFilter(f, status);
        Context context = context();
        if (!"OPEN".equals(status)) {
            List<ReviewViews.Item> items = views(
                    store.list(
                            filter,
                            order.getProperty(),
                            order.isAscending(),
                            pageable.getOffset(),
                            pageable.getPageSize()),
                    context);
            return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), store.count(filter));
        }
        List<ReviewViews.Item> items = views(store.list(filter, null, true, 0, MAX_OPEN_LISTED), context).stream()
                .filter(i -> f.direction() == null
                        || i.sightings().stream().anyMatch(s -> f.direction().equals(s.direction())))
                .filter(i -> f.band() == null
                        || (!i.candidates().isEmpty()
                                && f.band().equals(i.candidates().getFirst().confidence())))
                .filter(i -> !f.waiting() || i.waiting())
                .toList();
        int from = (int) Math.min(pageable.getOffset(), items.size());
        int to = Math.min(from + pageable.getPageSize(), items.size());
        return new PageResponse<>(
                items.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), items.size());
    }

    /** The items of the Resolved tab's filter for the CSV export (at most {@value #MAX_EXPORTED}). */
    @Transactional(readOnly = true)
    public List<ReviewViews.Item> export(Filter f, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(
                0, 1, sort, ReviewStore.SORTS, Sort.by(Sort.Direction.DESC, ReviewStore.SORTS.get("resolvedAt")));
        Sort.Order order = pageable.getSort().iterator().next();
        return views(
                store.list(storeFilter(f, "CLOSED"), order.getProperty(), order.isAscending(), 0, MAX_EXPORTED),
                context());
    }

    /** The Resolved tab's filter options and their counts. */
    @Transactional(readOnly = true)
    public ReviewViews.Facets facets() {
        ReviewStore.Facets facets = store.facets();
        return new ReviewViews.Facets(
                facets.types(),
                facets.actions(),
                facets.resolvers().stream()
                        .map(r -> new ReviewViews.Resolver(r.id(), r.name(), r.count()))
                        .toList());
    }

    private ReviewStore.Filter storeFilter(Filter f, String status) {
        List<String> resolvedBy = f.resolvedBy() == null ? List.of() : f.resolvedBy();
        return new ReviewStore.Filter(
                f.types(),
                status,
                f.from() == null ? null : time.startOfDay(f.from()),
                f.to() == null ? null : time.startOfDay(f.to().plusDays(1)),
                f.q(),
                f.actions(),
                resolvedBy.stream()
                        .filter(id -> !"SYSTEM".equals(id))
                        .map(UUID::fromString)
                        .toList(),
                resolvedBy.contains("SYSTEM"));
    }

    @Transactional(readOnly = true)
    public ReviewViews.Counts counts() {
        Map<String, Integer> all = store.counts();
        Map<String, Integer> open = new LinkedHashMap<>();
        for (String type : List.of("UNKNOWN", "UNCERTAIN", "REID", "LIVENESS")) {
            open.put(type, all.getOrDefault(type, 0));
        }
        return new ReviewViews.Counts(open, all.getOrDefault("RESOLVED", 0));
    }

    /** The Dashboard's review numbers; "waiting" uses NR-03's threshold, like the queue's waiting filter. */
    @Transactional(readOnly = true)
    public ReviewViews.Glance glance() {
        int minutes = rules.threshold("NR-03", NR03_DEFAULT_MINUTES);
        ReviewStore.Glance glance =
                store.glance(time.startOfDay(time.today()), time.now().minus(Duration.ofMinutes(minutes)));
        return new ReviewViews.Glance(
                counts().open(), glance.unknownToday(), glance.waiting(), glance.oldestWaiting(), minutes);
    }

    @Transactional(readOnly = true)
    public ReviewViews.Item item(String code) {
        ReviewStore.Item item = store.find(code).orElseThrow(() -> new Errors.NotFound("Review item not found."));
        return views(List.of(item), context()).getFirst();
    }

    @Transactional
    public ReviewViews.Applied decide(String code, DecisionInput decision) {
        AttendPrincipal me = currentUser.require();
        ReviewStore.Item item = store.lock(code).orElseThrow(() -> new Errors.NotFound("Review item not found."));
        apply(item, decision, me);
        queueChanged();
        String token = undo.issue(UNDO_KIND, me.employeeId(), Map.of("items", code));
        List<ReviewViews.Effect> effects = crossings.ofItem(item.id()).stream()
                .filter(c -> c.effect() != null && PUNCHES.contains(c.effect()))
                .map(c -> new ReviewViews.Effect(c.effect(), c.occurredAt()))
                .toList();
        return new ReviewViews.Applied(token, undo.windowSeconds(), List.of(code), effects);
    }

    /** The same decision on several items (the prototype's bulk buttons); one undo for all. */
    @Transactional
    public ReviewViews.Applied bulk(List<String> codes, Action action, UUID employeeId, String reason) {
        AttendPrincipal me = currentUser.require();
        String chosen = reason != null
                ? reason
                : switch (action) {
                    case CONFIRM, FALSE_ALARM -> "Correct identity";
                    case NOT_EMPLOYEE -> "Visitor";
                    case REJECT -> "Bad image";
                    default -> null;
                };
        List<String> done = new ArrayList<>();
        for (String code : new LinkedHashSet<>(codes)) {
            ReviewStore.Item item = store.lock(code).orElseThrow(() -> new Errors.NotFound(code + " was not found."));
            apply(item, new DecisionInput(action, employeeId, chosen, null, false, null), me);
            done.add(code);
        }
        queueChanged();
        String token = undo.issue(UNDO_KIND, me.employeeId(), Map.of("items", String.join(",", done)));
        return new ReviewViews.Applied(token, undo.windowSeconds(), done, List.of());
    }

    /** Restores the items, their crossings and any template, within the undo window; only for the decision's author. */
    @Transactional
    public void undo(String token) {
        AttendPrincipal me = currentUser.require();
        Map<String, String> data = undo.redeem(UNDO_KIND, me.employeeId(), token)
                .orElseThrow(() -> new Errors.Conflict("UNDO_EXPIRED", "This can no longer be undone."));
        List<String> codes = List.of(data.get("items").split(","));
        Instant now = time.now();
        for (String code : codes.reversed()) {
            ReviewStore.Item item = store.lock(code)
                    .filter(i -> "RESOLVED".equals(i.status()) && i.undoSnapshot() != null)
                    .filter(i -> i.undoUntil() != null && !i.undoUntil().isBefore(now))
                    .orElseThrow(() -> new Errors.Conflict("UNDO_EXPIRED", "This can no longer be undone."));
            Snapshot snapshot = json.readValue(item.undoSnapshot(), new TypeReference<Snapshot>() {});
            List<Sighting> current = crossings.ofItem(item.id());
            crossings.restore(snapshot.crossings(), current);
            if (item.templateAddedId() != null) {
                templates.removeFromReview(item.templateAddedId());
            }
            Action action = Action.valueOf(item.resolutionAction());
            if ("REID".equals(item.type()) && action.identifies()) {
                for (Sighting c : current) {
                    config.publishAfterCommit(
                            ConfigSignal.reidUnbind(item.resolutionEmployeeId(), c.crossingUid(), item.version() + 1L));
                }
            }
            store.reopen(item.id());
            audit.record(AuditCategory.REVIEW, "Undid review decision", code, action.label(), "Pending review", null);
        }
        queueChanged();
    }

    /** New or joined items from ingestion: the queue's badges refresh. */
    @TransactionalEventListener
    public void onQueueChanged(ReviewQueueChanged event) {
        publishCounts();
    }

    private void apply(ReviewStore.Item item, DecisionInput d, AttendPrincipal me) {
        if (!"OPEN".equals(item.status())) {
            throw new Errors.Conflict("ALREADY_RESOLVED", item.code() + " was resolved already.");
        }
        Action action = d.action();
        String type = item.type();
        if (!ALLOWED.get(type).contains(action)) {
            throw new Errors.BusinessRule(
                    "ACTION_NOT_ALLOWED",
                    action.label() + " is not available for a "
                            + TYPE_LABELS.get(type).toLowerCase(Locale.ROOT) + ".");
        }
        List<Sighting> before = crossings.ofItem(item.id());
        UUID top = topCandidate(before);
        UUID employee = switch (action) {
            case CONFIRM -> top;
            case FALSE_ALARM -> d.employeeId() != null ? d.employeeId() : top;
            case ASSIGN -> d.employeeId();
            default -> null;
        };
        boolean override = action == Action.ASSIGN && !"UNKNOWN".equals(type)
                || action == Action.FALSE_ALARM
                        && d.employeeId() != null
                        && !d.employeeId().equals(top);
        String reason = reason(action, type, d, override);
        String note = clean(d.note());
        if ("Other".equals(reason) && note == null) {
            throw invalid("note", "Add a note when the reason is “Other”.");
        }
        AuditActor actor = AuditActor.employee(me.employeeId(), me.displayName());
        Long templateId = null;
        String employeeName = null;
        if (action.identifies()) {
            if (employee == null) {
                throw invalid("employeeId", "Choose an employee.");
            }
            var person = employees.summaries(List.of(employee)).get(employee);
            if (person == null || !person.accountEnabled()) {
                throw invalid("employeeId", "Choose an enabled employee.");
            }
            employeeName = person.displayName();
            crossings.identify(before, employee, me.employeeId());
            if (d.addTemplate()) {
                if (!Set.of("UNKNOWN", "UNCERTAIN").contains(type)) {
                    throw new Errors.BusinessRule(
                            "TEMPLATE_NOT_ELIGIBLE",
                            "Templates are added only from unknown faces and uncertain matches.");
                }
                Sighting best = best(before, d.sightingCode());
                ReviewViews.Eligibility eligibility = eligibility(type, best, employee, employeeName);
                if (!eligibility.ok()) {
                    throw new Errors.BusinessRule("TEMPLATE_NOT_ELIGIBLE", eligibility.why());
                }
                templateId = templates.addFromReview(employee, best.faceCrop(), item.id(), item.code(), actor);
            }
            if ("REID".equals(type)) {
                for (Sighting c : before) {
                    config.publishAfterCommit(ConfigSignal.reidBind(employee, c.crossingUid(), item.version() + 1L));
                }
            }
        } else {
            crossings.decide(before, decisionOf(action), me.employeeId());
        }
        Instant now = time.now();
        store.resolve(
                item.id(),
                new ReviewStore.Resolution(
                        action.name(),
                        employee,
                        me.employeeId(),
                        now,
                        reason,
                        note,
                        templateId,
                        json.writeValueAsString(new Snapshot(before)),
                        now.plusSeconds(undo.windowSeconds())));
        audit.record(
                AuditCategory.REVIEW,
                action.label(),
                item.code() + (employeeName == null ? "" : " → " + employeeName),
                "Pending review",
                action.label() + (templateId == null ? "" : " · template added"),
                null);
    }

    private String reason(Action action, String type, DecisionInput d, boolean override) {
        String reason = clean(d.reason());
        switch (action) {
            case ASSIGN, FALSE_ALARM -> {
                if (reason == null && override) {
                    throw invalid("reason", "Choose a reason for the override.");
                }
                if (reason != null && !ASSIGN_REASONS.contains(reason)) {
                    throw invalid("reason", "Choose Correct identity, Wrong suggestion or Other.");
                }
                return reason == null ? "Correct identity" : reason;
            }
            case NOT_EMPLOYEE -> {
                if (reason == null) {
                    if (!"UNKNOWN".equals(type)) {
                        throw invalid("reason", "Choose a reason.");
                    }
                    return "Visitor";
                }
                if (!NOT_EMPLOYEE_REASONS.contains(reason)) {
                    throw invalid("reason", "Choose Visitor, Wrong suggestion or Other.");
                }
                return reason;
            }
            case CONFIRM -> {
                return "Correct identity";
            }
            case REJECT -> {
                return "Bad image";
            }
            default -> {
                return reason;
            }
        }
    }

    private static Decision decisionOf(Action action) {
        return switch (action) {
            case NOT_EMPLOYEE -> Decision.NOT_AN_EMPLOYEE;
            case SPOOF -> Decision.SPOOF_CONFIRMED;
            default -> Decision.REJECTED;
        };
    }

    /** What the views of one request share. */
    private record Context(ConfidenceBands bands, ActiveThresholds values, boolean showScores, Instant waitingBefore) {}

    private Context context() {
        int minutes = rules.threshold("NR-03", NR03_DEFAULT_MINUTES);
        ActiveThresholds values = recognition.activeValues();
        return new Context(
                new ConfidenceBands(values.accept()),
                values,
                currentUser.has(Permission.SETTINGS_RECOGNITION),
                time.now().minus(Duration.ofMinutes(minutes)));
    }

    private List<ReviewViews.Item> views(List<ReviewStore.Item> items, Context context) {
        Map<Long, List<Sighting>> sightings =
                crossings.ofItems(items.stream().map(ReviewStore.Item::id).toList());
        Set<UUID> candidateIds = new HashSet<>();
        sightings
                .values()
                .forEach(list ->
                        list.forEach(s -> candidateIds.addAll(candidates(s).keySet())));
        var cards = employees.cards(candidateIds);
        List<ReviewViews.Item> views = new ArrayList<>();
        for (ReviewStore.Item item : items) {
            List<Sighting> mine = sightings.getOrDefault(item.id(), List.of());
            List<ReviewViews.Candidate> candidates = new ArrayList<>();
            if (!"UNKNOWN".equals(item.type()) && !mine.isEmpty()) {
                candidates(mine.getFirst()).forEach((id, score) -> {
                    var card = cards.get(id);
                    candidates.add(new ReviewViews.Candidate(
                            id,
                            card == null ? "Unknown employee" : card.displayName(),
                            card == null ? null : card.department(),
                            context.bands().band(score),
                            context.showScores() ? score : null));
                });
            }
            Sighting best = best(mine, null);
            Sighting first = mine.isEmpty() ? null : mine.getFirst();
            boolean open = "OPEN".equals(item.status());
            views.add(new ReviewViews.Item(
                    item.code(),
                    item.type(),
                    TYPE_LABELS.get(item.type()),
                    item.status(),
                    item.label(),
                    item.createdAt(),
                    item.lastSightingAt(),
                    open && item.createdAt().isBefore(context.waitingBefore()),
                    first == null ? null : first.direction(),
                    mine.stream().map(s -> sighting(item.type(), s, context)).toList(),
                    candidates,
                    first == null ? null : reason(item.type(), first, candidates, context),
                    "REID".equals(item.type()) && first != null && !candidates.isEmpty()
                            ? cameIn(candidates.getFirst().employeeId(), first.occurredAt())
                            : null,
                    best == null ? null : best.code(),
                    open ? null : resolution(item)));
        }
        return views;
    }

    private ReviewViews.Resolution resolution(ReviewStore.Item item) {
        if ("EXPIRED".equals(item.status())) {
            // As the prototype shows it: the system closed the item when the unknown-face retention ran out.
            return new ReviewViews.Resolution(
                    "EXPIRED",
                    "Expired – no action",
                    null,
                    "System",
                    item.lastSightingAt()
                            .plus(Duration.ofHours(settings.privacy().unknownHours())),
                    item.resolutionReason(),
                    null,
                    false,
                    null);
        }
        if (item.resolutionAction() == null) {
            return null;
        }
        Action action = Action.valueOf(item.resolutionAction());
        boolean undoable = item.undoUntil() != null && item.undoUntil().isAfter(time.now());
        return new ReviewViews.Resolution(
                action.name(),
                action.label(),
                item.resolutionEmployeeId() == null
                        ? null
                        : new ReviewViews.PersonRef(item.resolutionEmployeeId(), item.resolutionEmployeeName()),
                item.resolvedByName() == null ? "System" : item.resolvedByName(),
                item.resolvedAt(),
                item.resolutionReason(),
                item.resolutionNote(),
                item.templateAddedId() != null,
                undoable ? item.undoUntil() : null);
    }

    private ReviewViews.Sighting sighting(String type, Sighting s, Context context) {
        return new ReviewViews.Sighting(
                s.code(),
                s.occurredAt(),
                s.direction(),
                s.cameraCode(),
                s.cameraName(),
                FaceQuality.of(s.quality() == null ? null : json.readTree(s.quality()), context.values()),
                s.faceCrop() != null && !s.cropsPurged(),
                s.bodyCrop() != null && !s.cropsPurged(),
                s.cropsPurged(),
                s.trackId(),
                s.liveness(),
                eligibility(type, s, null, null));
    }

    /** The prototype's reasonText and spoofHint, from what the worker measured and the active thresholds. */
    private static String reason(String type, Sighting first, List<ReviewViews.Candidate> candidates, Context context) {
        ActiveThresholds v = context.values();
        return switch (type) {
            case "UNCERTAIN" ->
                first.margin() != null && first.margin().doubleValue() < v.margin()
                        ? "Top two candidates are too close"
                        : String.format(
                                Locale.ROOT,
                                "Similarity is inside the review band (%.2f–%.2f)",
                                v.accept() - v.reviewBand(),
                                v.accept());
            case "REID" ->
                candidates.isEmpty()
                        ? "No body match among the people who came in today"
                        : String.format(
                                Locale.ROOT, "Body match below the auto-accept threshold (%.2f)", v.reidThreshold());
            case "LIVENESS" -> "The liveness check failed in at least 2 of the last 3 checks";
            default -> null;
        };
    }

    /** The top candidate's last IN before the exit, the same day. */
    private ReviewViews.CameIn cameIn(UUID employee, Instant exit) {
        Instant dayStart = time.startOfDay(time.zoned(exit).toLocalDate());
        return crossings
                .lastIn(employee, dayStart, exit)
                .map(c -> new ReviewViews.CameIn(c.code(), c.occurredAt(), c.faceCrop()))
                .orElse(null);
    }

    /** The worker's candidates of a crossing, best first: employee → score. */
    private Map<UUID, BigDecimal> candidates(Sighting s) {
        Map<UUID, BigDecimal> candidates = new LinkedHashMap<>();
        if (s.candidates() == null) {
            return candidates;
        }
        List<JsonNode> list = new ArrayList<>();
        json.readTree(s.candidates()).forEach(list::add);
        list.sort(Comparator.comparingDouble((JsonNode c) -> c.path("score").asDouble())
                .reversed());
        for (JsonNode c : list) {
            try {
                candidates.putIfAbsent(
                        UUID.fromString(c.path("employee_id").asString()),
                        BigDecimal.valueOf(c.path("score").asDouble()));
            } catch (IllegalArgumentException e) {
                // A candidate without a valid id is not shown.
            }
        }
        return candidates;
    }

    private UUID topCandidate(List<Sighting> sightings) {
        if (sightings.isEmpty()) {
            return null;
        }
        return candidates(sightings.getFirst()).keySet().stream().findFirst().orElse(null);
    }

    /** The chosen sighting, else the one with the best eye distance plus sharpness (the prototype's bestSighting). */
    private Sighting best(List<Sighting> sightings, String preferred) {
        if (preferred != null) {
            for (Sighting s : sightings) {
                if (s.code().equals(preferred)) {
                    return s;
                }
            }
            throw invalid("sightingCode", "That crossing is not part of this item.");
        }
        return sightings.stream()
                .max(Comparator.comparingInt(s -> {
                    JsonNode q = s.quality() == null ? null : json.readTree(s.quality());
                    return q == null
                            ? -1
                            : q.path("eye_px").asInt(0) + q.path("sharp").asInt(0);
                }))
                .orElse(null);
    }

    /** The prototype's {@code templateEligibility} (TemplateService); the consent check needs the employee. */
    private ReviewViews.Eligibility eligibility(String type, Sighting best, UUID employee, String employeeName) {
        var crop = new TemplateService.Crop(
                best == null ? null : best.faceCrop(),
                best == null ? null : best.quality(),
                "REID".equals(type),
                best == null || best.cropsPurged());
        var eligibility = templates.eligibility(employee, employeeName, crop);
        return new ReviewViews.Eligibility(eligibility.ok(), eligibility.why());
    }

    private void queueChanged() {
        AfterCommit.run(this::publishCounts);
    }

    private void publishCounts() {
        realtime.publish("review.counts", counts(), Permission.REVIEW_RESOLVE);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Errors.BusinessRule invalid(String field, String message) {
        return new Errors.BusinessRule("VALIDATION", message, List.of(new ErrorResponse.FieldError(field, message)));
    }
}
