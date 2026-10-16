package com.krztechnologies.attend.report.service;

import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.recognition.service.ConfidenceBands;
import com.krztechnologies.attend.recognition.service.RecognitionService;
import com.krztechnologies.attend.report.repository.ReportQueries;
import com.krztechnologies.attend.review.service.ReviewService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports (reports.view): enrolment coverage (the prototype's coverage groups by department, and who is not covered)
 * and recognition quality over a period (per day, what reviewers decided, the Re-ID rate, liveness alerts, who to
 * re-enrol). Everything is a SQL aggregate.
 */
@Service
public class ReportService {

    /** The prototype's coverage segments, in its order, with their labels. */
    public static final Map<String, String> GROUP_LABELS = groupLabels();

    /** The default period of the recognition report (the prototype's "Last 14 days"). */
    static final int DEFAULT_DAYS = 14;

    static final int MAX_DAYS = 92;

    /** Employees whose automatic face matches average under the accept threshold plus this stand out. */
    private static final double WEAK_MARGIN = 0.06;

    private static final int WEAK_SHOWN = 10;

    private final ReportQueries queries;
    private final RecognitionService recognition;
    private final CurrentUser currentUser;
    private final TimeService time;

    public ReportService(
            ReportQueries queries, RecognitionService recognition, CurrentUser currentUser, TimeService time) {
        this.queries = queries;
        this.recognition = recognition;
        this.currentUser = currentUser;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public ReportViews.Coverage coverage() {
        ReportQueries.Covered covered = queries.covered();
        Map<String, Map<String, Integer>> byDepartment = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (ReportQueries.DepartmentGroup g : queries.departments()) {
            names.put(g.code(), g.name());
            byDepartment
                    .computeIfAbsent(g.code(), code -> new LinkedHashMap<>())
                    .put(g.group(), g.count());
        }
        return new ReportViews.Coverage(
                covered.eligible(),
                covered.covered(),
                everyGroup(queries.groups()),
                queries.cameraLocations(),
                queries.noCameraLocations(),
                byDepartment.entrySet().stream()
                        .map(e -> new ReportViews.DepartmentCoverage(
                                e.getKey(), names.get(e.getKey()), everyGroup(e.getValue())))
                        .toList());
    }

    /** Sort keys of the coverage table: employee, department, location, face and why not covered (the default). */
    static final Map<String, Comparator<ReportQueries.Uncovered>> COVERAGE_SORTS = Map.of(
            "name", text(ReportQueries.Uncovered::name),
            "department", text(ReportQueries.Uncovered::department),
            "location", text(ReportQueries.Uncovered::location),
            "face", text(ReportQueries.Uncovered::faceStatus),
            "why", text(ReportQueries.Uncovered::group));

    /**
     * The coverage table's filters (null or empty lists = any).
     *
     * @param groups coverage groups other than TRACKED
     * @param departments department codes
     * @param q name or Employee ID
     */
    public record CoverageFilter(
            @Nullable List<String> groups,
            @Nullable List<String> departments,
            @Nullable String q) {}

    /** One page of the employees not (fully) covered; @param sort a key of {@link #COVERAGE_SORTS} (default why) */
    @Transactional(readOnly = true)
    public PageResponse<ReportViews.Uncovered> uncovered(
            CoverageFilter filter, @Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        Pageable pageable = coveragePage(page, size, sort);
        List<ReportViews.Uncovered> rows = uncovered(filter, pageable.getSort());
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageResponse<>(
                rows.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), rows.size());
    }

    /** Every employee of the filter in the table's order (CSV). */
    @Transactional(readOnly = true)
    public List<ReportViews.Uncovered> uncoveredExport(CoverageFilter filter, @Nullable String sort) {
        return uncovered(filter, coveragePage(0, 1, sort).getSort());
    }

    /** The coverage table's filter options with their counts over every employee not covered. */
    @Transactional(readOnly = true)
    public ReportViews.CoverageFacets coverageFacets() {
        List<ReportQueries.Uncovered> rows = queries.uncovered();
        Map<String, Long> groups =
                rows.stream().collect(Collectors.groupingBy(ReportQueries.Uncovered::group, Collectors.counting()));
        Map<String, Long> departments = rows.stream()
                .filter(u -> u.departmentCode() != null)
                .collect(Collectors.groupingBy(ReportQueries.Uncovered::departmentCode, Collectors.counting()));
        return new ReportViews.CoverageFacets(
                GROUP_LABELS.entrySet().stream()
                        .filter(e -> !e.getKey().equals("TRACKED"))
                        .map(e -> new ReportViews.Option(
                                e.getKey(),
                                e.getValue(),
                                groups.getOrDefault(e.getKey(), 0L).intValue()))
                        .toList(),
                queries.allDepartments().stream()
                        .map(d -> new ReportViews.Option(
                                d.code(),
                                d.name(),
                                departments.getOrDefault(d.code(), 0L).intValue()))
                        .toList());
    }

    private List<ReportViews.Uncovered> uncovered(CoverageFilter filter, Sort sort) {
        Sort.Order order = sort.iterator().next();
        Comparator<ReportQueries.Uncovered> comparator = COVERAGE_SORTS.get(order.getProperty());
        String q = filter.q() == null || filter.q().isBlank()
                ? null
                : filter.q().trim().toLowerCase(Locale.ROOT);
        return queries.uncovered().stream()
                .filter(u -> isEmpty(filter.groups()) || filter.groups().contains(u.group()))
                .filter(u ->
                        isEmpty(filter.departments()) || filter.departments().contains(u.departmentCode()))
                .filter(u -> q == null
                        || u.name().toLowerCase(Locale.ROOT).contains(q)
                        || (u.employeeId() != null
                                && u.employeeId().toLowerCase(Locale.ROOT).contains(q)))
                .sorted((order.isAscending() ? comparator : comparator.reversed())
                        .thenComparing(text(ReportQueries.Uncovered::name)))
                .map(u -> new ReportViews.Uncovered(
                        u.id(),
                        u.name(),
                        u.employeeId(),
                        u.department(),
                        u.location(),
                        u.faceStatus(),
                        u.faceReason(),
                        u.group(),
                        u.templates()))
                .toList();
    }

    private static Pageable coveragePage(@Nullable Integer page, @Nullable Integer size, @Nullable String sort) {
        Map<String, String> fields =
                COVERAGE_SORTS.keySet().stream().collect(Collectors.toMap(Function.identity(), Function.identity()));
        return PageRequestSpec.toPageable(page, size, sort, fields, Sort.by("why"));
    }

    private static boolean isEmpty(@Nullable List<String> values) {
        return values == null || values.isEmpty();
    }

    /** Case-insensitive text order with nulls last. */
    private static <T> Comparator<T> text(Function<T, String> value) {
        return Comparator.comparing(value, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /** @param from first day (default: 13 days before {@code to}); @param to last day (default today); ≤ 92 days */
    @Transactional(readOnly = true)
    public ReportViews.Recognition recognition(LocalDate from, LocalDate to) {
        LocalDate last = to == null ? time.today() : to;
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1L) : from;
        if (last.isBefore(first)) {
            throw new Errors.BadRequest("INVALID_RANGE", "The end date is before the start date.");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw new Errors.BadRequest("RANGE_TOO_LONG", "Choose a period of at most 92 days.");
        }
        Instant start = time.startOfDay(first);
        Instant end = time.startOfDay(last.plusDays(1));
        String zone = time.zone().getId();
        Map<LocalDate, ReportQueries.CrossingDay> crossings = queries.crossingDays(start, end, zone).stream()
                .collect(Collectors.toMap(ReportQueries.CrossingDay::day, Function.identity()));
        Map<LocalDate, ReportQueries.ReviewDay> reviews = queries.reviewDays(start, end, zone).stream()
                .collect(Collectors.toMap(ReportQueries.ReviewDay::day, Function.identity()));
        Map<LocalDate, BigDecimal> uptime = queries.uptime(first, last);
        List<ReportViews.Day> days = new ArrayList<>();
        for (LocalDate day = last; !day.isBefore(first); day = day.minusDays(1)) {
            days.add(day(day, crossings.get(day), reviews.get(day), uptime.get(day)));
        }
        boolean scores = currentUser.has(Permission.SETTINGS_RECOGNITION);
        ConfidenceBands bands = recognition.confidenceBands();
        BigDecimal average = queries.averageFaceScore(start, end)
                .map(ReportService::threePlaces)
                .orElse(null);
        return new ReportViews.Recognition(
                first,
                last,
                totals(crossings.values()),
                scores ? average : null,
                bands.band(average),
                queries.activeProfile().orElse(null),
                days,
                queries.weakMatches(start, end, bands.accept() + WEAK_MARGIN, WEAK_SHOWN).stream()
                        .map(w -> {
                            BigDecimal score = threePlaces(w.averageScore());
                            return new ReportViews.WeakMatch(
                                    w.id(),
                                    w.name(),
                                    w.employeeId(),
                                    w.faceStatus(),
                                    scores ? score : null,
                                    bands.band(score),
                                    w.reviews());
                        })
                        .toList(),
                decisions(queries.decisions(start, end)));
    }

    private static ReportViews.Day day(
            LocalDate day, ReportQueries.CrossingDay c, ReportQueries.ReviewDay r, BigDecimal uptime) {
        if (c == null) {
            c = new ReportQueries.CrossingDay(day, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new ReportViews.Day(
                day,
                c.counted(),
                c.faceAuto(),
                c.reidAuto(),
                c.pendingOrReview(),
                c.notIdentified(),
                c.faceAuto() + c.reidAuto(),
                c.reviewed(),
                c.outs(),
                c.outByReid(),
                r == null ? 0 : r.unknownFaces(),
                c.livenessFailed(),
                c.duplicates(),
                uptime,
                r == null || r.waitMinutes() == null ? null : Math.round(r.waitMinutes() * 10) / 10.0);
    }

    private static ReportViews.Totals totals(Iterable<ReportQueries.CrossingDay> days) {
        int crossings = 0;
        int ins = 0;
        int outs = 0;
        int automatic = 0;
        int inByFace = 0;
        int outByReid = 0;
        int reviewed = 0;
        int resolved = 0;
        int liveness = 0;
        for (ReportQueries.CrossingDay d : days) {
            crossings += d.counted();
            ins += d.ins();
            outs += d.outs();
            automatic += d.faceAuto() + d.reidAuto();
            inByFace += d.inByFace();
            outByReid += d.outByReidAuto();
            reviewed += d.reviewed();
            resolved += d.resolvedInReview();
            liveness += d.livenessFailed();
        }
        return new ReportViews.Totals(
                crossings, ins, outs, automatic, inByFace, outByReid, reviewed, resolved, liveness);
    }

    /** Every review action with its label, then "Expired – no action" (the prototype's chart). */
    private static List<ReportViews.Decision> decisions(Map<String, Integer> counts) {
        List<ReportViews.Decision> decisions = new ArrayList<>();
        for (ReviewService.Action action : ReviewService.Action.values()) {
            decisions.add(
                    new ReportViews.Decision(action.name(), action.label(), counts.getOrDefault(action.name(), 0)));
        }
        decisions.add(new ReportViews.Decision("EXPIRED", "Expired – no action", counts.getOrDefault("EXPIRED", 0)));
        return decisions;
    }

    private static Map<String, Integer> everyGroup(Map<String, Integer> counts) {
        Map<String, Integer> groups = new LinkedHashMap<>();
        GROUP_LABELS.keySet().forEach(group -> groups.put(group, counts.getOrDefault(group, 0)));
        return groups;
    }

    private static BigDecimal threePlaces(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private static Map<String, String> groupLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("TRACKED", "Tracked");
        labels.put("REENROL", "Needs re-enrolment");
        labels.put("PENDING", "Consent pending");
        labels.put("NO_FACE", "No face profile");
        labels.put("NO_CAMERA", "No camera at location");
        labels.put("EXEMPT", "Exempt");
        return Collections.unmodifiableMap(labels);
    }
}
