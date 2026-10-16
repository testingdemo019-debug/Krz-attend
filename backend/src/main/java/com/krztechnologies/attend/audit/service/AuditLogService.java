package com.krztechnologies.attend.audit.service;

import com.krztechnologies.attend.audit.repository.AuditLogQueries;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings → Audit log (read-only): every sign-in, change, export and correction with who did it, their roles at the
 * time, the IP address and the before/after values. Filters by category, person, IST date range and free text; the
 * CSV export uses the same filters.
 */
@Service
public class AuditLogService {

    /** A larger export must be narrowed down (the file is built in memory). */
    static final int MAX_EXPORT_ROWS = 50_000;

    private static final Map<AuditCategory, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry(AuditCategory.SIGN_IN, "Sign-in"),
            Map.entry(AuditCategory.USERS_ROLES, "Users & roles"),
            Map.entry(AuditCategory.ORGANISATION, "Organisation"),
            Map.entry(AuditCategory.EMPLOYEES, "Employees"),
            Map.entry(AuditCategory.ENROLMENT, "Enrolment"),
            Map.entry(AuditCategory.REVIEW, "Review"),
            Map.entry(AuditCategory.ATTENDANCE, "Attendance"),
            Map.entry(AuditCategory.CAMERAS, "Cameras"),
            Map.entry(AuditCategory.RECOGNITION, "Recognition"),
            Map.entry(AuditCategory.SETTINGS, "Settings"),
            Map.entry(AuditCategory.HRMS, "HRMS"),
            Map.entry(AuditCategory.PRIVACY, "Privacy"),
            Map.entry(AuditCategory.EXPORT, "Export"),
            Map.entry(AuditCategory.LIVE_VIEW, "Live View"),
            Map.entry(AuditCategory.SYSTEM, "System"));

    private final AuditLogQueries queries;
    private final CsvExportService csv;
    private final TimeService time;
    private final JsonMapper json;

    public AuditLogService(AuditLogQueries queries, CsvExportService csv, TimeService time, JsonMapper json) {
        this.queries = queries;
        this.csv = csv;
        this.time = time;
        this.json = json;
    }

    /**
     * The filters of the list and the export; null or empty means "any".
     *
     * @param categories several categories
     * @param actors employee IDs or actors' names as recorded (e.g. "System", "Directory sync")
     * @param from first IST day (inclusive)
     * @param to last IST day (inclusive)
     * @param q free text: person, action, target, before/after values or the AU code
     */
    public record AuditQuery(
            @Nullable List<AuditCategory> categories,
            @Nullable List<String> actors,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable String q) {}

    /** A filter value and how many entries have it. */
    public record Option(String value, String label, int count) {}

    /**
     * The table's filter options with their counts over the date range and text of the query: every category, and
     * everyone who did something then.
     */
    public record Facets(List<Option> category, List<Option> actor) {}

    /**
     * One audit entry.
     *
     * @param actor the name shown ("System" for system actors)
     * @param roles the actor's roles at the time
     * @param details structured extras (counts, file names), or null
     */
    public record AuditEntry(
            String code,
            Instant occurredAt,
            @Nullable UUID actorId,
            String actor,
            @Nullable String roles,
            @Nullable String ip,
            String category,
            String action,
            @Nullable String target,
            @Nullable String before,
            @Nullable String after,
            @Nullable JsonNode details,
            @Nullable String traceId) {}

    /** @param page sorted by a key of {@link AuditLogQueries#SORTS} (ts, actor, category, action) */
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> list(AuditQuery query, Pageable page) {
        AuditLogQueries.Filter filter = filter(query);
        Sort.Order order = page.getSort().iterator().next();
        List<AuditEntry> items =
                queries
                        .find(filter, order.getProperty(), order.isAscending(), page.getOffset(), page.getPageSize())
                        .stream()
                        .map(this::toEntry)
                        .toList();
        return new PageResponse<>(items, page.getPageNumber(), page.getPageSize(), queries.count(filter));
    }

    @Transactional(readOnly = true)
    public Facets facets(AuditQuery query) {
        Map<String, List<AuditLogQueries.ValueCount>> counts = queries.facets(filter(query));
        Map<String, Integer> categories = counts.get("category").stream()
                .collect(Collectors.toMap(AuditLogQueries.ValueCount::value, AuditLogQueries.ValueCount::count));
        return new Facets(
                Arrays.stream(AuditCategory.values())
                        .map(c -> new Option(c.name(), CATEGORY_LABELS.get(c), categories.getOrDefault(c.name(), 0)))
                        .toList(),
                counts.get("actor").stream()
                        .map(a -> new Option(a.value(), a.value(), a.count()))
                        .toList());
    }

    /** CSV with the list's filters, in its order; audited as an export. */
    @Transactional
    public ResponseEntity<StreamingResponseBody> export(AuditQuery query, Sort sort) {
        AuditLogQueries.Filter filter = filter(query);
        long total = queries.count(filter);
        if (total > MAX_EXPORT_ROWS) {
            throw new Errors.BusinessRule(
                    "EXPORT_TOO_LARGE",
                    "That is " + total + " entries; narrow the dates or filters to at most " + MAX_EXPORT_ROWS + ".");
        }
        Sort.Order order = sort.iterator().next();
        List<AuditLogQueries.Row> rows =
                queries.find(filter, order.getProperty(), order.isAscending(), 0, MAX_EXPORT_ROWS);
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        List<CsvExportService.Column<AuditLogQueries.Row>> columns = List.of(
                new CsvExportService.Column<>(
                        "Time",
                        r -> time.zoned(r.occurredAt())
                                .truncatedTo(ChronoUnit.SECONDS)
                                .toOffsetDateTime()
                                .format(iso)),
                new CsvExportService.Column<>(
                        "Who",
                        r -> r.actorRoles() == null ? r.actorLabel() : r.actorLabel() + " (" + r.actorRoles() + ")"),
                new CsvExportService.Column<>("Category", r -> categoryLabel(r.category())),
                new CsvExportService.Column<>(
                        "Action", r -> r.target() == null ? r.action() : r.action() + ": " + r.target()),
                new CsvExportService.Column<>("Change", r -> blank(r.before()) + " -> " + blank(r.after())),
                new CsvExportService.Column<>("IP address", AuditLogQueries.Row::actorIp),
                new CsvExportService.Column<>("Entry", AuditLogQueries.Row::code));
        return csv.export("audit-log", "Audit log" + range(query), columns, rows);
    }

    private AuditLogQueries.Filter filter(AuditQuery query) {
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw new Errors.BadRequest("INVALID_RANGE", "The start date must not be after the end date.");
        }
        List<UUID> actorIds = new ArrayList<>();
        List<String> actorLabels = new ArrayList<>();
        for (String actor : query.actors() == null ? List.<String>of() : query.actors()) {
            if (actor == null || actor.isBlank()) {
                continue;
            }
            try {
                actorIds.add(UUID.fromString(actor.strip()));
            } catch (IllegalArgumentException notAnId) {
                actorLabels.add(actor.strip());
            }
        }
        return new AuditLogQueries.Filter(
                query.categories() == null
                        ? List.of()
                        : query.categories().stream().map(AuditCategory::name).toList(),
                actorIds,
                actorLabels,
                query.from() == null ? null : time.startOfDay(query.from()),
                query.to() == null ? null : time.startOfDay(query.to().plusDays(1)),
                query.q() == null || query.q().isBlank() ? null : query.q().strip());
    }

    private AuditEntry toEntry(AuditLogQueries.Row row) {
        return new AuditEntry(
                row.code(),
                row.occurredAt(),
                row.actorId(),
                row.actorLabel(),
                row.actorRoles(),
                row.actorIp(),
                row.category(),
                row.action(),
                row.target(),
                row.before(),
                row.after(),
                row.details() == null ? null : json.readTree(row.details()),
                row.traceId());
    }

    private static String categoryLabel(String category) {
        try {
            return CATEGORY_LABELS.getOrDefault(AuditCategory.valueOf(category), category);
        } catch (IllegalArgumentException unknown) {
            return category;
        }
    }

    /** The list's page and sort: {@code ts,desc} (newest first) unless another column is chosen. */
    public static Pageable pageable(@Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        Map<String, String> keys = AuditLogQueries.SORTS.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), Function.identity()));
        return PageRequestSpec.toPageable(page, size, sort, keys, Sort.by(Sort.Direction.DESC, "ts"));
    }

    private static String range(AuditQuery query) {
        if (query.from() == null && query.to() == null) {
            return "";
        }
        return " (" + (query.from() == null ? "…" : query.from()) + " – " + (query.to() == null ? "…" : query.to())
                + ")";
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
