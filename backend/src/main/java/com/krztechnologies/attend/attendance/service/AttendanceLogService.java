package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.attendance.repository.AttendanceQueries;
import com.krztechnologies.attend.attendance.repository.AttendanceStore;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeDirectory.AttendanceProfile;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attendance log: one row per employee, one cell per shift day (status codes from the punch rules), with the
 * prototype's totals; days without a row yet are computed first (R7). At most 31 days at a time.
 */
@Service
public class AttendanceLogService {

    /** The longest range the log shows. */
    static final int MAX_DAYS = 31;

    /** The statuses the "Present" total counts (the prototype's {@code presentLike}). */
    private static final Set<String> PRESENT_LIKE = Set.of(
            "CHECKED_OUT",
            "LATE",
            "EARLY_EXIT",
            "HALF_DAY",
            "INSIDE",
            "STEPPED_OUT",
            "WORKED_ON_HOLIDAY",
            "WORKED_ON_WEEKLY_OFF",
            "MISSING_CHECK_OUT",
            "MISSING_CHECK_IN");

    private static final Set<String> MISSING = Set.of("MISSING_CHECK_OUT", "MISSING_CHECK_IN");

    private final EmployeeDirectory employees;
    private final AttendanceRecomputeService attendance;
    private final AttendanceQueries queries;
    private final TimeService time;

    public AttendanceLogService(
            EmployeeDirectory employees,
            AttendanceRecomputeService attendance,
            AttendanceQueries queries,
            TimeService time) {
        this.employees = employees;
        this.attendance = attendance;
        this.queries = queries;
        this.time = time;
    }

    /**
     * The log filters (all optional).
     *
     * @param from first shift day (default six days before {@code to})
     * @param to last shift day (default today)
     * @param departments department codes (null or empty = any; likewise the other lists)
     * @param shifts shifts worked on any day of the range
     * @param statuses statuses on any day of the range ("Has status")
     * @param includeNotTracked also employees not tracked on any day of the range
     * @param q name or Employee ID
     */
    public record Filter(
            LocalDate from,
            LocalDate to,
            List<String> departments,
            List<String> shifts,
            List<String> statuses,
            UUID employeeId,
            boolean includeNotTracked,
            String q) {}

    /** Sort keys of the grid: by name, or by a total. */
    static final Map<String, String> SORTS = Map.of(
            "name", "name",
            "present", "present",
            "late", "late",
            "absent", "absent",
            "missing", "missing",
            "worked", "worked");

    /** The grid; {@code sort} is {@code name} (default, A–Z) or a total. */
    @Transactional
    public PageResponse<AttendanceViews.LogRow> log(Filter f, Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(page, size, sort, SORTS, Sort.by(Sort.Direction.ASC, "name"));
        List<AttendanceViews.LogRow> rows = new ArrayList<>(rows(f));
        rows.sort(comparator(pageable.getSort().iterator().next()));
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageResponse<>(
                rows.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), rows.size());
    }

    /** Every row of the grid in the table's order (CSV). */
    @Transactional
    public List<AttendanceViews.LogRow> all(Filter f, String sort) {
        List<AttendanceViews.LogRow> rows = new ArrayList<>(rows(f));
        rows.sort(comparator(PageRequestSpec.toPageable(0, 1, sort, SORTS, Sort.by(Sort.Direction.ASC, "name"))
                .getSort()
                .iterator()
                .next()));
        return rows;
    }

    /** The filter options with their counts among the employees of the range (search and filters left out). */
    @Transactional
    public AttendanceViews.LogFacets facets(LocalDate from, LocalDate to, boolean includeNotTracked) {
        Filter all = new Filter(from, to, null, null, null, null, includeNotTracked, null);
        List<Built> built = build(all, range(all));
        return new AttendanceViews.LogFacets(
                AttendanceReads.options(built, b -> b.person().department()),
                AttendanceReads.optionsOfAll(
                        built,
                        b -> b.days().stream()
                                .filter(d -> d != null && d.shiftCode() != null)
                                .map(d -> new AttendanceViews.Ref(d.shiftCode(), d.shiftName()))
                                .toList()),
                AttendanceReads.optionsOfAll(
                        built,
                        b -> b.days().stream()
                                .filter(d -> d != null && d.status() != null)
                                .map(d -> new AttendanceViews.Ref(d.status(), d.status()))
                                .toList()));
    }

    private static Comparator<AttendanceViews.LogRow> comparator(Sort.Order order) {
        Comparator<AttendanceViews.LogRow> byName =
                Comparator.comparing(r -> r.employee().name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<AttendanceViews.LogRow> by = switch (order.getProperty()) {
            case "present" -> Comparator.comparingInt(r -> r.totals().present());
            case "late" -> Comparator.comparingInt(r -> r.totals().late());
            case "absent" -> Comparator.comparingInt(r -> r.totals().absent());
            case "missing" -> Comparator.comparingInt(r -> r.totals().missing());
            case "worked" -> Comparator.comparingInt(r -> r.totals().workedMinutes());
            default -> byName;
        };
        by = order.isAscending() ? by : by.reversed();
        return by.thenComparing(byName);
    }

    /** One line per employee-day that applies (the daily-detail CSV). */
    @Transactional
    public List<AttendanceViews.DailyRow> daily(Filter f) {
        Range range = range(f);
        List<AttendanceViews.DailyRow> lines = new ArrayList<>();
        for (Built b : build(f, range)) {
            for (AttendanceQueries.DayRow d : b.days()) {
                if (d != null && d.status() != null) {
                    lines.add(new AttendanceViews.DailyRow(
                            b.person(),
                            d.shiftDay(),
                            d.shiftName(),
                            d.status(),
                            d.checkInAt(),
                            d.checkOutAt(),
                            d.workedMinutes(),
                            d.late(),
                            d.earlyExit(),
                            d.finalised()));
                }
            }
        }
        return lines;
    }

    private record Range(LocalDate from, LocalDate to, List<LocalDate> days) {}

    /** An employee and their days of the range (null where no row exists, e.g. future days). */
    private record Built(AttendanceViews.PersonRef person, List<AttendanceQueries.DayRow> days) {}

    private List<AttendanceViews.LogRow> rows(Filter f) {
        Range range = range(f);
        List<AttendanceViews.LogRow> rows = new ArrayList<>();
        for (Built b : build(f, range)) {
            List<AttendanceViews.LogCell> cells = new ArrayList<>();
            int present = 0;
            int late = 0;
            int absent = 0;
            int missing = 0;
            int worked = 0;
            for (int i = 0; i < range.days().size(); i++) {
                AttendanceQueries.DayRow d = b.days().get(i);
                if (d == null) {
                    cells.add(new AttendanceViews.LogCell(range.days().get(i), null, null, false, false, null, null));
                    continue;
                }
                cells.add(new AttendanceViews.LogCell(
                        d.shiftDay(),
                        d.status(),
                        d.statusCode(),
                        d.late(),
                        d.finalised(),
                        d.checkInAt(),
                        d.checkOutAt()));
                present += PRESENT_LIKE.contains(d.status()) ? 1 : 0;
                late += d.late() ? 1 : 0;
                absent += "ABSENT".equals(d.status()) ? 1 : 0;
                missing += MISSING.contains(d.status()) ? 1 : 0;
                worked += d.workedMinutes() == null ? 0 : d.workedMinutes();
            }
            rows.add(new AttendanceViews.LogRow(
                    b.person(), cells, new AttendanceViews.LogTotals(present, late, absent, missing, worked)));
        }
        return rows;
    }

    private List<Built> build(Filter f, Range range) {
        List<AttendanceProfile> people =
                employees.attendanceProfiles(employees.attendanceEmployees(range.from())).values().stream()
                        .filter(p -> p.joinDate() != null && !p.joinDate().isAfter(range.to()))
                        .filter(p -> p.leftOn() == null || !p.leftOn().isBefore(range.from()))
                        .filter(p -> f.employeeId() == null || f.employeeId().equals(p.id()))
                        .filter(p -> f.includeNotTracked()
                                || range.days().stream()
                                        .anyMatch(d -> AttendanceRecomputeService.employment(p)
                                                .trackedOn(d)))
                        .toList();
        List<UUID> ids = people.stream().map(AttendanceProfile::id).toList();
        List<AttendanceStore.EmployeeDay> wanted = new ArrayList<>();
        for (UUID id : ids) {
            range.days().forEach(d -> wanted.add(new AttendanceStore.EmployeeDay(id, d)));
        }
        attendance.recomputeMissing(wanted);
        Map<UUID, Map<LocalDate, AttendanceQueries.DayRow>> byEmployee = new HashMap<>();
        for (AttendanceQueries.DayRow d : queries.days(ids, range.from(), range.to())) {
            byEmployee.computeIfAbsent(d.employeeId(), k -> new HashMap<>()).put(d.shiftDay(), d);
        }
        Map<UUID, AttendanceViews.PersonRef> persons = AttendanceReads.persons(queries.people(ids));
        List<Built> built = new ArrayList<>();
        for (AttendanceProfile p : people) {
            Map<LocalDate, AttendanceQueries.DayRow> mine = byEmployee.getOrDefault(p.id(), Map.of());
            List<AttendanceQueries.DayRow> days =
                    range.days().stream().map(mine::get).toList();
            AttendanceViews.PersonRef person = persons.get(p.id());
            if (matches(person, days, f)) {
                built.add(new Built(person, days));
            }
        }
        built.sort(Comparator.comparing(b -> b.person().name(), String.CASE_INSENSITIVE_ORDER));
        return built;
    }

    private static boolean matches(AttendanceViews.PersonRef person, List<AttendanceQueries.DayRow> days, Filter f) {
        if (!AttendanceReads.any(
                f.departments(),
                person.department() == null ? null : person.department().code())) {
            return false;
        }
        if (f.shifts() != null
                && !f.shifts().isEmpty()
                && days.stream().noneMatch(d -> d != null && f.shifts().contains(d.shiftCode()))) {
            return false;
        }
        if (f.statuses() != null
                && !f.statuses().isEmpty()
                && days.stream().noneMatch(d -> d != null && f.statuses().contains(d.status()))) {
            return false;
        }
        if (f.q() != null && !f.q().isBlank()) {
            String needle = f.q().trim().toLowerCase(Locale.ROOT);
            String haystack = (person.name() + " " + (person.employeeId() == null ? "" : person.employeeId()))
                    .toLowerCase(Locale.ROOT);
            return haystack.contains(needle);
        }
        return true;
    }

    private Range range(Filter f) {
        LocalDate to = f.to() == null ? time.today() : f.to();
        LocalDate from = f.from() == null ? to.minusDays(6) : f.from();
        if (to.isBefore(from)) {
            throw new Errors.BadRequest("INVALID_RANGE", "The end date can’t be before the start date.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new Errors.BadRequest("INVALID_RANGE", "The log shows up to " + MAX_DAYS + " days at a time.");
        }
        List<LocalDate> days = from.datesUntil(to.plusDays(1)).toList();
        return new Range(from, to, days);
    }
}
