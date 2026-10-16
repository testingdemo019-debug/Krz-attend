package com.krztechnologies.attend.hrms.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.feature.FeatureFlags;
import com.krztechnologies.attend.common.settings.HrmsSettings;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.hrms.repository.OutboxQueries;
import com.krztechnologies.attend.hrms.repository.OutboxStore;
import com.krztechnologies.attend.rules.service.OutboxStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings → KRZ HRMS: the connection and payload format, the outbox with its filters, and the operator's actions on it
 * (R8): retry one failed entry, retry every failed entry, release the punches held while HRMS was not connected. Every
 * action is audited (category HRMS).
 */
@Service
public class HrmsOutboxService {

    /** The largest export (narrow the filters beyond that). */
    static final int MAX_EXPORT_ROWS = 50_000;

    /** The prototype's master data table. */
    private static final List<HrmsViews.MasterData> MASTER_DATA = List.of(
            new HrmsViews.MasterData(
                    "Employees (Employee ID, department, designation, location)", "Managed in KRZ Attend", true),
            new HrmsViews.MasterData("Departments", "Managed in KRZ Attend", true),
            new HrmsViews.MasterData("Shifts and allocation", "Managed in KRZ Attend", true),
            new HrmsViews.MasterData("Holidays", "Managed in KRZ Attend", true),
            new HrmsViews.MasterData("Identity (name, work email, account enabled)", "Microsoft Entra ID", false));

    /** The format samples until the outbox has entries of its own. */
    private static final String PUNCH_SAMPLE = """
            {"punchId":"PU-100123","employeeId":"EMP-1234","timestamp":"2026-10-12T09:02:14+05:30","direction":"IN",\
            "type":"CHECK_IN","method":"FACE","deviceId":"CAM-01","eventId":"EV-100123","final":true}""";

    private static final String VOID_SAMPLE = """
            {"punchId":"PU-100123","reason":"Voided in KRZ Attend: Wrong person identified"}""";

    private final OutboxQueries queries;
    private final OutboxStore store;
    private final HrmsClient client;
    private final FeatureFlags features;
    private final SettingsService settings;
    private final AuditService audit;
    private final TimeService time;
    private final JsonMapper json;

    public HrmsOutboxService(
            OutboxQueries queries,
            OutboxStore store,
            HrmsClient client,
            FeatureFlags features,
            SettingsService settings,
            AuditService audit,
            TimeService time,
            JsonMapper json) {
        this.queries = queries;
        this.store = store;
        this.client = client;
        this.features = features;
        this.settings = settings;
        this.audit = audit;
        this.time = time;
        this.json = json;
    }

    /**
     * The list filters (all optional).
     *
     * @param statuses any of these statuses
     * @param from first shift day
     * @param to last shift day
     * @param q OB code, punch ID, employee name, Employee ID or error text
     */
    public record ListFilter(
            @Nullable List<OutboxStatus> statuses,
            @Nullable List<String> kinds,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable String q) {}

    @Transactional(readOnly = true)
    public HrmsViews.Overview overview() {
        HrmsSettings retries = settings.hrms();
        return new HrmsViews.Overview(
                client.connection(),
                features.hrmsEnabled(),
                new HrmsViews.Retries(retries.maxAttempts(), retries.backoffSeconds()),
                settings.attendanceRules().pushMode().name(),
                MASTER_DATA,
                json.readTree(queries.latestPayload("PUNCH").orElse(PUNCH_SAMPLE)),
                json.readTree(queries.latestPayload("VOID").orElse(VOID_SAMPLE)),
                counts());
    }

    /** Entries per outbox status, every status present (the page header, Settings → System). */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        Map<String, Long> found = queries.counts();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status.name(), found.getOrDefault(status.name(), 0L));
        }
        return counts;
    }

    /** Who has punches blocked for a missing Employee ID, by name (the Dashboard's alert). */
    @Transactional(readOnly = true)
    public List<String> blockedEmployees() {
        return queries.blockedEmployees();
    }

    /** @param sort {@code created} (default, newest first), {@code punch}, {@code employee}, {@code day}, … */
    @Transactional(readOnly = true)
    public PageResponse<HrmsViews.Entry> list(ListFilter filter, Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(
                page,
                size,
                sort,
                OutboxQueries.SORTS,
                Sort.by(Sort.Direction.DESC, OutboxQueries.SORTS.get("created")));
        Sort.Order order = pageable.getSort().iterator().next();
        OutboxQueries.Filter f = filter(filter);
        List<HrmsViews.Entry> items = queries
                .list(f, order.getProperty(), order.isAscending(), pageable.getOffset(), pageable.getPageSize())
                .stream()
                .map(this::entry)
                .toList();
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), queries.count(f));
    }

    /** Every entry of the filter in the list's order (CSV). */
    @Transactional(readOnly = true)
    public List<HrmsViews.Entry> export(ListFilter filter, @Nullable String sort) {
        OutboxQueries.Filter f = filter(filter);
        if (queries.count(f) > MAX_EXPORT_ROWS) {
            throw new Errors.BusinessRule(
                    "EXPORT_TOO_LARGE", "More than 50,000 outbox entries match. Narrow the filters.");
        }
        Sort.Order order = PageRequestSpec.toPageable(
                        0,
                        1,
                        sort,
                        OutboxQueries.SORTS,
                        Sort.by(Sort.Direction.DESC, OutboxQueries.SORTS.get("created")))
                .getSort()
                .iterator()
                .next();
        return queries.list(f, order.getProperty(), order.isAscending(), 0, MAX_EXPORT_ROWS).stream()
                .map(this::entry)
                .toList();
    }

    /** A failed entry is queued again with fresh attempts (held instead while HRMS is not connected). */
    @Transactional
    public HrmsViews.Entry retry(String code) {
        OutboxStore.Entry entry = store.lock(code).orElseThrow(() -> new Errors.NotFound("Outbox entry not found."));
        if (entry.status() != OutboxStatus.FAILED) {
            throw new Errors.Conflict("NOT_FAILED", "Only failed entries can be retried.");
        }
        OutboxStatus to = queuedStatus();
        store.requeue(entry.id(), OutboxStatus.FAILED, to, time.now());
        audit.record(
                AuditCategory.HRMS,
                "Retried push",
                entry.punchId(),
                OutboxStatus.FAILED.label(),
                to.label(),
                Map.of("entry", entry.code()));
        return queries.one(code).map(this::entry).orElseThrow();
    }

    /** Every failed entry is queued again (the prototype's "Retry n failed"). */
    @Transactional
    public HrmsViews.Requeued retryFailed() {
        OutboxStatus to = queuedStatus();
        int entries = store.requeue(null, OutboxStatus.FAILED, to, time.now());
        if (entries > 0) {
            audit.record(
                    AuditCategory.HRMS,
                    "Retried all failed pushes",
                    entries + (entries == 1 ? " entry" : " entries"),
                    OutboxStatus.FAILED.label(),
                    to.label(),
                    null);
        }
        return new HrmsViews.Requeued(entries);
    }

    /** The punches held while HRMS was not connected become PENDING; the sender takes them oldest first. */
    @Transactional
    public HrmsViews.Requeued releaseHeld() {
        if (!features.hrmsEnabled()) {
            throw new Errors.Conflict("HRMS_NOT_CONNECTED", "KRZ HRMS isn’t connected yet, so held punches stay held.");
        }
        int entries = store.requeue(null, OutboxStatus.HELD_NOT_CONNECTED, OutboxStatus.PENDING, time.now());
        if (entries > 0) {
            audit.record(
                    AuditCategory.HRMS,
                    "Released held punches",
                    entries + (entries == 1 ? " entry" : " entries"),
                    OutboxStatus.HELD_NOT_CONNECTED.label(),
                    OutboxStatus.PENDING.label(),
                    null);
        }
        return new HrmsViews.Requeued(entries);
    }

    /** PENDING while HRMS is connected, else held like every new punch (R8). */
    private OutboxStatus queuedStatus() {
        return features.hrmsEnabled() ? OutboxStatus.PENDING : OutboxStatus.HELD_NOT_CONNECTED;
    }

    private static OutboxQueries.Filter filter(ListFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw new Errors.BadRequest("INVALID_RANGE", "The end date is before the start date.");
        }
        List<String> statuses = filter.statuses() == null
                ? List.of()
                : filter.statuses().stream().map(OutboxStatus::name).toList();
        return new OutboxQueries.Filter(statuses, filter.kinds(), filter.from(), filter.to(), filter.q());
    }

    private HrmsViews.Entry entry(OutboxQueries.Row r) {
        JsonNode payload = json.readTree(r.payload());
        boolean punch = "PUNCH".equals(r.kind());
        Instant punchAt = punch && payload.hasNonNull("timestamp")
                ? OffsetDateTime.parse(payload.path("timestamp").asString()).toInstant()
                : null;
        return new HrmsViews.Entry(
                r.code(),
                r.kind(),
                r.punchId(),
                punch ? payload.path("type").asString() : "VOID",
                punchAt,
                new HrmsViews.EmployeeRef(r.employeeId(), r.employeeName(), r.hrmsEmployeeId()),
                r.shiftDay(),
                r.status(),
                OutboxStatus.valueOf(r.status()).label(),
                r.attempts(),
                r.lastError(),
                r.createdAt(),
                r.lastAttemptAt(),
                r.nextAttemptAt(),
                r.sentAt(),
                payload);
    }
}
