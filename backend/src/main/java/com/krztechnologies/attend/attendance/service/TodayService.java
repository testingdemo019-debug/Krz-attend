package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.attendance.repository.AttendanceQueries;
import com.krztechnologies.attend.attendance.repository.AttendanceStore;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeDirectory.AttendanceProfile;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Today: every enabled employee who has joined, on their current shift day (R3), from the materialised days (created
 * lazily when missing, R7), with the prototype's quick filters, KPI counts and the evacuation list.
 */
@Service
public class TodayService {

    /** The quick filters of Today, in the prototype's order. */
    public static final Map<String, Predicate<AttendanceViews.TodayRow>> QUICK = quickFilters();

    /** Sort keys of the table. */
    static final Map<String, String> SORTS = Map.of(
            "name", "name",
            "department", "department",
            "shift", "shift",
            "status", "status",
            "in", "in",
            "last", "last",
            "out", "out",
            "now", "now",
            "worked", "worked",
            "hrms", "hrms");

    private final EmployeeDirectory employees;
    private final OrganisationDirectory organisation;
    private final AttendanceRecomputeService attendance;
    private final AttendanceQueries queries;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final SettingsService settings;
    private final TimeService time;

    public TodayService(
            EmployeeDirectory employees,
            OrganisationDirectory organisation,
            AttendanceRecomputeService attendance,
            AttendanceQueries queries,
            AuditService audit,
            CurrentUser currentUser,
            SettingsService settings,
            TimeService time) {
        this.employees = employees;
        this.organisation = organisation;
        this.attendance = attendance;
        this.queries = queries;
        this.audit = audit;
        this.currentUser = currentUser;
        this.settings = settings;
        this.time = time;
    }

    /**
     * The table filters (all optional).
     *
     * @param quick a key of {@link #QUICK} (default {@code all})
     * @param departments department codes (null or empty = any; likewise the other lists)
     * @param q name, Employee ID or department
     */
    public record Filter(
            String quick,
            List<String> departments,
            List<String> locations,
            List<String> shifts,
            List<String> statuses,
            List<String> employmentTypes,
            String q) {}

    /** @param sort {@code in} (default, latest first), {@code name}, {@code department}, {@code shift}, … */
    @Transactional
    public PageResponse<AttendanceViews.TodayRow> list(Filter f, Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "in"));
        List<AttendanceViews.TodayRow> rows =
                filtered(f, pageable.getSort().iterator().next());
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageResponse<>(
                rows.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), rows.size());
    }

    /** Every row of the filter in the table's order (CSV). */
    @Transactional
    public List<AttendanceViews.TodayRow> export(Filter f, String sort) {
        Sort.Order order = PageRequestSpec.toPageable(0, 1, sort, SORTS, Sort.by(Sort.Direction.DESC, "in"))
                .getSort()
                .iterator()
                .next();
        return filtered(f, order);
    }

    /** The filter options with their counts among the rows of the quick filter (the prototype's count column). */
    @Transactional
    public AttendanceViews.TodayFacets facets(String quick) {
        List<AttendanceViews.TodayRow> rows =
                rows().stream().filter(QUICK.get(quick == null ? "all" : quick)).toList();
        return new AttendanceViews.TodayFacets(
                AttendanceReads.options(rows, r -> r.employee().department()),
                AttendanceReads.options(
                        rows,
                        r -> new AttendanceViews.Ref(r.shift().code(), r.shift().name())),
                AttendanceReads.options(rows, r -> new AttendanceViews.Ref(r.status(), r.status())),
                AttendanceReads.options(rows, r -> r.employee().location()),
                AttendanceReads.options(
                        rows,
                        r -> r.employee().employmentType() == null
                                ? null
                                : new AttendanceViews.Ref(
                                        r.employee().employmentType(),
                                        r.employee().employmentType())));
    }

    private List<AttendanceViews.TodayRow> filtered(Filter f, Sort.Order order) {
        Predicate<AttendanceViews.TodayRow> quick = QUICK.get(f.quick() == null ? "all" : f.quick());
        return rows().stream()
                .filter(quick)
                .filter(r -> AttendanceReads.any(
                        f.departments(),
                        r.employee().department() == null
                                ? null
                                : r.employee().department().code()))
                .filter(r -> AttendanceReads.any(
                        f.locations(),
                        r.employee().location() == null
                                ? null
                                : r.employee().location().code()))
                .filter(r -> AttendanceReads.any(f.shifts(), r.shift().code()))
                .filter(r -> AttendanceReads.any(f.statuses(), r.status()))
                .filter(r ->
                        AttendanceReads.any(f.employmentTypes(), r.employee().employmentType()))
                .filter(r -> matches(r, f.q()))
                .sorted(comparator(order))
                .toList();
    }

    @Transactional
    public AttendanceViews.TodayStats stats() {
        List<AttendanceViews.TodayRow> rows = rows();
        List<AttendanceViews.TodayRow> tracked =
                rows.stream().filter(AttendanceViews.TodayRow::tracked).toList();
        Function<String, Integer> status =
                s -> (int) tracked.stream().filter(r -> s.equals(r.status())).count();
        int working = (int)
                tracked.stream().filter(r -> !r.holiday() && !r.weeklyOff()).count();
        Map<String, Integer> quick = new LinkedHashMap<>();
        QUICK.forEach((key, filter) ->
                quick.put(key, (int) rows.stream().filter(filter).count()));
        return new AttendanceViews.TodayStats(
                tracked.size(),
                rows.size() - tracked.size(),
                count(tracked, r -> "INSIDE".equals(r.now())),
                count(tracked, r -> r.checkIn() != null),
                count(tracked, AttendanceViews.TodayRow::late),
                status.apply("NOT_YET_IN"),
                status.apply("ABSENT"),
                count(tracked, r -> r.checkOut() != null && !"INSIDE".equals(r.now())),
                count(tracked, AttendanceViews.TodayRow::earlyExit),
                status.apply("STEPPED_OUT"),
                status.apply("MISSING_CHECK_OUT"),
                status.apply("MISSING_CHECK_IN"),
                working,
                tracked.size() - working,
                quick);
    }

    /** Who is inside now, by name; opening it is audited. */
    @Transactional
    public AttendanceViews.Evacuation evacuation() {
        List<Entry> inside = entries().stream()
                .filter(e -> "INSIDE".equals(e.row().now()))
                .sorted(Comparator.comparing(e -> e.row().employee().name()))
                .toList();
        List<AttendanceViews.EvacuationRow> people = new ArrayList<>();
        for (Entry e : inside) {
            var person = e.row().employee();
            people.add(new AttendanceViews.EvacuationRow(
                    people.size() + 1,
                    person.name(),
                    person.employeeId(),
                    person.department() == null ? null : person.department().name(),
                    e.lastIn()));
        }
        audit.record(AuditCategory.ATTENDANCE, "Opened evacuation list", people.size() + " inside", null, null, null);
        return new AttendanceViews.Evacuation(
                settings.general().orgName(),
                queries.cameraNames(),
                time.now(),
                currentUser.require().displayName(),
                queries.unidentifiedInsSince(time.startOfDay(time.today())),
                people);
    }

    /** A row of Today and the last counted IN (the evacuation list). */
    private record Entry(AttendanceViews.TodayRow row, Instant lastIn) {}

    /** Every row of Today, unfiltered. */
    private List<AttendanceViews.TodayRow> rows() {
        return entries().stream().map(Entry::row).toList();
    }

    private List<Entry> entries() {
        Instant now = time.now();
        LocalDate today = time.today();
        ShiftCalendar calendar = organisation.calendar();
        List<AttendanceProfile> people =
                employees.attendanceProfiles(employees.attendanceEmployees(today)).values().stream()
                        .filter(p -> p.accountEnabled()
                                && p.joinDate() != null
                                && !p.joinDate().isAfter(today))
                        .toList();
        Map<UUID, LocalDate> dayOf = new LinkedHashMap<>();
        people.forEach(p -> dayOf.put(p.id(), calendar.shiftDayOf(AttendanceRecomputeService.person(p), now)));
        attendance.recomputeMissing(dayOf.entrySet().stream()
                .map(e -> new AttendanceStore.EmployeeDay(e.getKey(), e.getValue()))
                .toList());
        Map<UUID, AttendanceQueries.DayRow> days =
                queries.days(dayOf.keySet(), today.minusDays(1), today.plusDays(1)).stream()
                        .filter(d -> d.shiftDay().equals(dayOf.get(d.employeeId())))
                        .collect(Collectors.toMap(AttendanceQueries.DayRow::employeeId, Function.identity()));
        Map<UUID, AttendanceViews.PersonRef> persons = AttendanceReads.persons(queries.people(dayOf.keySet()));
        List<Entry> rows = new ArrayList<>();
        for (AttendanceProfile p : people) {
            AttendanceQueries.DayRow d = days.get(p.id());
            if (d == null) {
                continue;
            }
            ShiftTerms terms = calendar.effective(AttendanceRecomputeService.person(p), d.shiftDay())
                    .terms();
            rows.add(new Entry(
                    new AttendanceViews.TodayRow(
                            persons.get(p.id()),
                            d.shiftDay(),
                            AttendanceReads.shift(terms),
                            d.status(),
                            d.statusCode(),
                            d.finalised(),
                            d.late(),
                            d.lateMinutes(),
                            d.earlyExit(),
                            d.missingCheckOut(),
                            d.checkInAt(),
                            d.checkInMethod(),
                            d.lastMovementAt(),
                            d.lastDirection(),
                            d.checkOutAt(),
                            d.checkOutFinal(),
                            !d.tracked() || d.lastMovementAt() == null ? null : d.inside() ? "INSIDE" : "OUTSIDE",
                            d.workedMinutes(),
                            d.checkInAt() == null ? null : d.hrmsStatus(),
                            d.tracked(),
                            p.trackingLabel(),
                            d.holiday(),
                            d.weeklyOff()),
                    d.lastInAt()));
        }
        return rows;
    }

    private static boolean matches(AttendanceViews.TodayRow r, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        String haystack = (r.employee().name() + " "
                        + (r.employee().employeeId() == null ? "" : r.employee().employeeId()) + " "
                        + (r.employee().department() == null
                                ? ""
                                : r.employee().department().name()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(needle);
    }

    private static Comparator<AttendanceViews.TodayRow> comparator(Sort.Order order) {
        Comparator<AttendanceViews.TodayRow> by = switch (order.getProperty()) {
            case "name" -> Comparator.comparing(r -> r.employee().name(), String.CASE_INSENSITIVE_ORDER);
            case "department" ->
                Comparator.comparing(
                        r -> r.employee().department() == null
                                ? null
                                : r.employee().department().name(),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "shift" -> Comparator.comparing(r -> r.shift().timing());
            case "status" ->
                Comparator.comparing(AttendanceViews.TodayRow::status, Comparator.nullsLast(String::compareTo));
            case "last" ->
                Comparator.comparing(AttendanceViews.TodayRow::lastMovement, Comparator.nullsFirst(Instant::compareTo));
            case "out" ->
                Comparator.comparing(AttendanceViews.TodayRow::checkOut, Comparator.nullsFirst(Instant::compareTo));
            case "worked" ->
                Comparator.comparing(
                        AttendanceViews.TodayRow::workedMinutes, Comparator.nullsFirst(Integer::compareTo));
            case "now" -> Comparator.comparing(r -> "INSIDE".equals(r.now()) ? 0 : 1);
            case "hrms" ->
                Comparator.comparing(AttendanceViews.TodayRow::hrmsStatus, Comparator.nullsFirst(String::compareTo));
            default ->
                Comparator.comparing(AttendanceViews.TodayRow::checkIn, Comparator.nullsFirst(Instant::compareTo));
        };
        by = order.isAscending() ? by : by.reversed();
        return by.thenComparing(r -> r.employee().name(), String.CASE_INSENSITIVE_ORDER);
    }

    private static int count(List<AttendanceViews.TodayRow> rows, Predicate<AttendanceViews.TodayRow> filter) {
        return (int) rows.stream().filter(filter).count();
    }

    /** The prototype's {@code TODAY_FILTERS}. */
    private static Map<String, Predicate<AttendanceViews.TodayRow>> quickFilters() {
        Set<String> missing = Set.of("MISSING_CHECK_OUT", "MISSING_CHECK_IN");
        Map<String, Predicate<AttendanceViews.TodayRow>> filters = new LinkedHashMap<>();
        filters.put("all", AttendanceViews.TodayRow::tracked);
        filters.put("inside", r -> r.tracked() && "INSIDE".equals(r.now()));
        filters.put("checked-in", r -> r.tracked() && r.checkIn() != null);
        filters.put("late", r -> r.tracked() && r.late());
        filters.put("not-in", r -> "NOT_YET_IN".equals(r.status()));
        filters.put("stepped-out", r -> "STEPPED_OUT".equals(r.status()));
        filters.put("checked-out", r -> r.tracked() && r.checkOut() != null && !"INSIDE".equals(r.now()));
        filters.put("early-exit", r -> r.tracked() && r.earlyExit());
        filters.put("absent", r -> "ABSENT".equals(r.status()));
        filters.put("missing", r -> missing.contains(r.status()));
        filters.put("off", r -> r.tracked() && (r.holiday() || r.weeklyOff()));
        filters.put("not-tracked", r -> !r.tracked());
        return Collections.unmodifiableMap(filters);
    }
}
