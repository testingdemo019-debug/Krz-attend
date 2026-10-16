package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.attendance.repository.AttendanceStore;
import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.realtime.SseHub;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsChangedEvent;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.transaction.AfterCommit;
import com.krztechnologies.attend.crossing.service.CrossingTimelines;
import com.krztechnologies.attend.crossing.service.CrossingsChanged;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeDirectory.AttendanceProfile;
import com.krztechnologies.attend.hrms.service.HrmsOutboxReconciler;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.PunchRulesEngine;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.Crossing;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.DayResult;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.Timeline;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import com.krztechnologies.attend.rules.service.TimeFormat;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * R7: recomputes employee-days in the caller's transaction. For each day: the identified crossings, the engine
 * ({@code evaluateDay}), the crossing effects, the {@code attendance_day} row and the HRMS outbox (R8); after the commit
 * the realtime event {@code attendance.changed}. One run loads the calendar, the rules and the organisation ids once.
 */
@Service
public class AttendanceRecomputeService {

    /** Organisation and employment changes recompute at most this many days back. */
    static final int MAX_BACK_DAYS = 31;

    private final EmployeeDirectory employees;
    private final OrganisationDirectory organisation;
    private final CrossingTimelines timelines;
    private final HrmsOutboxReconciler outbox;
    private final AttendanceStore store;
    private final SettingsService settings;
    private final TimeService time;
    private final SseHub realtime;
    private final EntityManager entityManager;

    public AttendanceRecomputeService(
            EmployeeDirectory employees,
            OrganisationDirectory organisation,
            CrossingTimelines timelines,
            HrmsOutboxReconciler outbox,
            AttendanceStore store,
            SettingsService settings,
            TimeService time,
            SseHub realtime,
            EntityManager entityManager) {
        this.employees = employees;
        this.organisation = organisation;
        this.timelines = timelines;
        this.outbox = outbox;
        this.store = store;
        this.settings = settings;
        this.time = time;
        this.realtime = realtime;
        this.entityManager = entityManager;
    }

    /** What one recompute run evaluates against. */
    private record Run(ShiftCalendar calendar, PunchRulesEngine engine, AttendanceStore.Ids ids, Instant now) {}

    /** A crossing, correction or review decision changed a day. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCrossingsChanged(CrossingsChanged change) {
        entityManager.flush();
        recompute(change.employeeId(), change.shiftDay());
    }

    /**
     * An organisation, employment or tracking change (P-23): the scope's days in the window are recomputed, after the
     * crossings of those days are filed under the shift days of the new calendar (R3). Only days up to today, at most
     * {@value #MAX_BACK_DAYS} days back.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAttendanceAffected(AttendanceAffected change) {
        entityManager.flush();
        LocalDate today = time.today();
        LocalDate from = max(change.from(), today.minusDays(MAX_BACK_DAYS));
        LocalDate to = change.to().isAfter(today) ? today : change.to();
        if (from.isAfter(to)) {
            return;
        }
        Run run = run();
        int days = 0;
        for (AttendanceProfile profile : affected(change.scope(), run.calendar(), from, to)) {
            ShiftCalendar.Person person = person(profile);
            Set<LocalDate> due = new TreeSet<>(from.datesUntil(to.plusDays(1)).toList());
            due.addAll(timelines.refile(
                    profile.id(),
                    time.startOfDay(from.minusDays(1)),
                    time.startOfDay(to.plusDays(2)),
                    ts -> run.calendar().shiftDayOf(person, ts)));
            for (LocalDate day : due) {
                if (!day.isAfter(today)) {
                    recompute(run, profile, day);
                    days++;
                }
            }
        }
        if (days > 0) {
            changed(Map.of("from", from.toString(), "to", to.toString()));
        }
    }

    /** New attendance rules apply to yesterday and today (the prototype's window), after the save committed. */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSettingsChanged(SettingsChangedEvent event) {
        if (SettingsArea.ATTENDANCE_RULES.key().equals(event.area())) {
            LocalDate today = time.today();
            recompute(employees.attendanceEmployees(today.minusDays(1)), today.minusDays(1), today);
        }
    }

    /** The employees an {@link AttendanceAffected} scope covers in the window. */
    private Collection<AttendanceProfile> affected(
            AttendanceAffected.Scope scope, ShiftCalendar calendar, LocalDate from, LocalDate to) {
        if (scope instanceof AttendanceAffected.Employees e) {
            return employees.attendanceProfiles(e.ids()).values();
        }
        return employees.attendanceProfiles(employees.attendanceEmployees(from)).values().stream()
                .filter(p -> switch (scope) {
                    case AttendanceAffected.Department d -> Long.valueOf(d.id()).equals(p.departmentId());
                    case AttendanceAffected.Location l -> l.id() == null || l.id().equals(p.locationId());
                    case AttendanceAffected.Shift s ->
                        from.datesUntil(to.plusDays(1))
                                .anyMatch(day -> calendar.effective(person(p), day)
                                        .terms()
                                        .shiftCode()
                                        .equals(s.code()));
                    case AttendanceAffected.Employees e -> e.ids().contains(p.id());
                })
                .toList();
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** One employee-day; null when the employee does not exist. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DayResult recompute(UUID employeeId, LocalDate day) {
        AttendanceProfile profile =
                employees.attendanceProfiles(List.of(employeeId)).get(employeeId);
        if (profile == null) {
            return null;
        }
        DayResult result = recompute(run(), profile, day);
        changed(Map.of("employeeId", employeeId.toString(), "day", day.toString()));
        return result;
    }

    /**
     * Every day from {@code from} to {@code to} (inclusive) of these employees (bulk recompute after organisation or
     * rule changes).
     *
     * @return how many employee-days were recomputed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recompute(Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        Run run = run();
        int days = 0;
        for (AttendanceProfile profile :
                employees.attendanceProfiles(employeeIds).values()) {
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                recompute(run, profile, day);
                days++;
            }
        }
        if (days > 0) {
            changed(Map.of("from", from.toString(), "to", to.toString()));
        }
        return days;
    }

    /**
     * One day of these employees (day close), with the results.
     *
     * @return the evaluated day per employee
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, DayResult> recomputeDay(Collection<UUID> employeeIds, LocalDate day) {
        Run run = run();
        Map<UUID, DayResult> results = new LinkedHashMap<>();
        for (AttendanceProfile profile :
                employees.attendanceProfiles(employeeIds).values()) {
            results.put(profile.id(), recompute(run, profile, day));
        }
        if (!results.isEmpty()) {
            changed(Map.of("from", day.toString(), "to", day.toString()));
        }
        return results;
    }

    /**
     * R7 time-driven transitions (every minute): rows whose status depends on the clock, and today's rows of everyone
     * who has none yet (the new day, new employees).
     *
     * @return how many employee-days were recomputed
     */
    @Transactional
    public int refreshTimeDriven() {
        LocalDate today = time.today();
        List<AttendanceStore.EmployeeDay> due = new ArrayList<>(store.timeDriven(today.minusDays(2), time.now()));
        store.withoutRow(today).forEach(id -> due.add(new AttendanceStore.EmployeeDay(id, today)));
        if (due.isEmpty()) {
            return 0;
        }
        Run run = run();
        Map<UUID, AttendanceProfile> profiles = employees.attendanceProfiles(
                due.stream().map(AttendanceStore.EmployeeDay::employeeId).collect(Collectors.toSet()));
        int days = 0;
        for (AttendanceStore.EmployeeDay d : due) {
            AttendanceProfile profile = profiles.get(d.employeeId());
            if (profile != null) {
                recompute(run, profile, d.shiftDay());
                days++;
            }
        }
        changed(Map.of("from", today.minusDays(2).toString(), "to", today.toString()));
        return days;
    }

    /**
     * Creates the rows that do not exist yet (Today and the log create them lazily, R7); days after today are skipped.
     *
     * @return how many rows were created
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recomputeMissing(Collection<AttendanceStore.EmployeeDay> wanted) {
        LocalDate today = time.today();
        List<AttendanceStore.EmployeeDay> due =
                wanted.stream().filter(d -> !d.shiftDay().isAfter(today)).toList();
        if (due.isEmpty()) {
            return 0;
        }
        Set<UUID> ids =
                due.stream().map(AttendanceStore.EmployeeDay::employeeId).collect(Collectors.toSet());
        LocalDate from = due.stream()
                .map(AttendanceStore.EmployeeDay::shiftDay)
                .min(LocalDate::compareTo)
                .orElseThrow();
        Set<AttendanceStore.EmployeeDay> existing = store.existing(ids, from, today);
        List<AttendanceStore.EmployeeDay> missing =
                due.stream().filter(d -> !existing.contains(d)).toList();
        if (missing.isEmpty()) {
            return 0;
        }
        Run run = run();
        Map<UUID, AttendanceProfile> profiles = employees.attendanceProfiles(ids);
        int created = 0;
        for (AttendanceStore.EmployeeDay d : missing) {
            AttendanceProfile profile = profiles.get(d.employeeId());
            if (profile != null) {
                recompute(run, profile, d.shiftDay());
                created++;
            }
        }
        return created;
    }

    /** Today, the log and open day drawers refetch (after the commit). */
    private void changed(Map<String, String> payload) {
        AfterCommit.run(() -> realtime.publish("attendance.changed", payload, Permission.ATTENDANCE_VIEW_ALL));
    }

    private Run run() {
        PunchRulesEngine engine = new PunchRulesEngine(
                settings.attendanceRules(),
                time.zone(),
                TimeFormat.of(settings.general().timeFormat()));
        return new Run(organisation.calendar(), engine, store.ids(), time.now());
    }

    /**
     * The day as it is and as it would be after {@code change} to its crossings, evaluated now; nothing is stored
     * (the correction preview).
     */
    public record Preview(List<Crossing> crossings, DayResult before, DayResult after) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Preview preview(UUID employeeId, LocalDate day, UnaryOperator<List<Crossing>> change) {
        AttendanceProfile profile =
                employees.attendanceProfiles(List.of(employeeId)).get(employeeId);
        Run run = run();
        List<Crossing> crossings = timelines.of(employeeId, day);
        return new Preview(
                crossings,
                evaluate(run, profile, day, crossings),
                evaluate(run, profile, day, change.apply(crossings)));
    }

    private DayResult recompute(Run run, AttendanceProfile profile, LocalDate day) {
        DayResult result = evaluate(run, profile, day, timelines.of(profile.id(), day));
        timelines.storeEffects(result.timeline().effects());
        store.upsert(row(run.ids(), profile, result));
        outbox.reconcile(profile.id(), day, run.engine().desiredPunches(result));
        return result;
    }

    private static DayResult evaluate(Run run, AttendanceProfile profile, LocalDate day, List<Crossing> crossings) {
        return run.engine()
                .evaluateDay(run.calendar(), person(profile), employment(profile), day, crossings, run.now());
    }

    /** The calendar's view of an employee. */
    static ShiftCalendar.Person person(AttendanceProfile profile) {
        return new ShiftCalendar.Person(profile.id(), profile.departmentId(), profile.locationId());
    }

    /** R5's view of an employee. */
    static PunchRulesEngine.Employment employment(AttendanceProfile profile) {
        return new PunchRulesEngine.Employment(
                profile.joinDate(),
                profile.leftOn(),
                profile.accountEnabled(),
                profile.tracked(),
                profile.notTrackedLabel());
    }

    private static AttendanceStore.DayRow row(AttendanceStore.Ids ids, AttendanceProfile profile, DayResult result) {
        ShiftCalendar.Effective effective = result.effective();
        ShiftTerms terms = effective.terms();
        ShiftCalendar.Times times = result.times();
        boolean applicable = result.applicable();
        Timeline t = result.timeline();
        return new AttendanceStore.DayRow(
                profile.id(),
                result.day(),
                id(ids.shifts(), terms.shiftCode()),
                id(ids.versions(), AttendanceStore.Ids.version(terms.shiftCode(), terms.versionFrom())),
                effective.allocation() == null
                        ? null
                        : id(ids.allocations(), effective.allocation().code()),
                effective.source().name(),
                times.windowStart(),
                times.start(),
                times.end(),
                times.cutoff(),
                times.close(),
                result.tracked(),
                result.tracked() ? null : profile.notTrackedReason(),
                result.holiday() == null
                        ? null
                        : id(ids.holidays(), result.holiday().code()),
                result.weeklyOff(),
                applicable ? result.status().name() : null,
                result.statusCode(),
                applicable ? idOf(t.checkIn()) : null,
                applicable ? idOf(t.checkOut()) : null,
                applicable && t.checkOutFinal(),
                applicable && t.inside(),
                applicable && t.steppedOut(),
                applicable && t.late(),
                applicable ? t.lateMinutes() : null,
                applicable && t.earlyExit(),
                applicable && t.missingCheckOut(),
                applicable ? t.workedMinutes() : null,
                applicable && t.lastMovement() != null ? t.lastMovement().ts() : null,
                applicable && t.finalised(),
                result.computedAt());
    }

    private static Long idOf(Crossing crossing) {
        return crossing == null ? null : crossing.id();
    }

    private static long id(Map<String, Long> ids, String key) {
        Long id = ids.get(key);
        if (id == null) {
            throw new IllegalStateException("No organisation row for " + key);
        }
        return id;
    }
}
