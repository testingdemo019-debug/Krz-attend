package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.attendance.repository.AttendanceStore;
import com.krztechnologies.attend.attendance.repository.DayCloseStore;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeDirectory.AttendanceProfile;
import com.krztechnologies.attend.notification.service.NotificationRequest;
import com.krztechnologies.attend.notification.service.NotificationService;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.AttendanceStatus;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.DayResult;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R13 day close: for the last two days, groups the tracked employees (not on a holiday or weekly off) by their
 * effective shift; every (shift, day) whose close has passed and that has no run yet is recomputed (which finalises
 * the days and reconciles the HRMS outbox), stored as {@code DC-<day>-<shift>}, audited "Day close finished", and NR-08
 * goes out when check-outs are missing. Each (shift, day) is its own transaction; running it again does nothing.
 */
@Service
public class DayCloseService {

    private static final int LOOKBACK_DAYS = 2;

    private final EmployeeDirectory employees;
    private final OrganisationDirectory organisation;
    private final AttendanceRecomputeService attendance;
    private final AttendanceStore attendanceStore;
    private final DayCloseStore store;
    private final NotificationService notifications;
    private final AuditService audit;
    private final TimeService time;
    private final TransactionTemplate transactions;

    public DayCloseService(
            EmployeeDirectory employees,
            OrganisationDirectory organisation,
            AttendanceRecomputeService attendance,
            AttendanceStore attendanceStore,
            DayCloseStore store,
            NotificationService notifications,
            AuditService audit,
            TimeService time,
            TransactionTemplate transactions) {
        this.employees = employees;
        this.organisation = organisation;
        this.attendance = attendance;
        this.attendanceStore = attendanceStore;
        this.store = store;
        this.notifications = notifications;
        this.audit = audit;
        this.time = time;
        this.transactions = transactions;
    }

    /** Who started a run. */
    public enum Trigger {
        SCHEDULE,
        MANUAL
    }

    /** A day close run as Settings → System lists it. */
    public record RunView(
            String code,
            String shiftCode,
            String shiftName,
            LocalDate shiftDay,
            Instant ranAt,
            String trigger,
            @Nullable String triggeredBy,
            int employees,
            int finalised,
            int absent,
            int missingCheckOuts) {}

    /** The employees of one (shift, day). */
    private record Group(ShiftTerms terms, ShiftCalendar.Times times, List<UUID> employees) {}

    /**
     * Closes every due (shift, day).
     *
     * @param actor {@link AuditActor#SYSTEM} for the schedule, the administrator for "Run day close now"
     * @return the runs made now (empty when nothing was due)
     */
    public List<RunView> closeDue(Trigger trigger, AuditActor actor) {
        Instant now = time.now();
        LocalDate today = time.today();
        ShiftCalendar calendar = organisation.calendar();
        var profiles = employees
                .attendanceProfiles(employees.attendanceEmployees(today.minusDays(LOOKBACK_DAYS)))
                .values();
        Map<String, Long> shiftIds = attendanceStore.ids().shifts();
        List<RunView> made = new ArrayList<>();
        for (int back = LOOKBACK_DAYS; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            for (Group group : groups(calendar, profiles, day).values()) {
                long shiftId = shiftIds.get(group.terms().shiftCode());
                if (now.isBefore(group.times().close()) || store.exists(shiftId, day)) {
                    continue;
                }
                RunView run = transactions.execute(status -> close(group, day, shiftId, trigger, actor));
                if (run != null) {
                    made.add(run);
                }
            }
        }
        return made;
    }

    @Transactional(readOnly = true)
    public PageResponse<RunView> runs(Integer page, Integer size) {
        Pageable pageable = PageRequestSpec.toPageable(page, size, null, Map.of(), Sort.unsorted());
        List<RunView> items = store.list(pageable.getOffset(), pageable.getPageSize()).stream()
                .map(r -> new RunView(
                        r.code(),
                        r.shiftCode(),
                        r.shiftName(),
                        r.shiftDay(),
                        r.ranAt(),
                        r.trigger(),
                        r.triggeredBy() == null ? "System" : r.triggeredBy(),
                        r.employees(),
                        r.finalised(),
                        r.absent(),
                        r.missingCheckOuts()))
                .toList();
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), store.count());
    }

    private static Map<String, Group> groups(
            ShiftCalendar calendar, Iterable<AttendanceProfile> profiles, LocalDate day) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (AttendanceProfile profile : profiles) {
            if (!AttendanceRecomputeService.employment(profile).trackedOn(day)) {
                continue;
            }
            ShiftCalendar.Person person = AttendanceRecomputeService.person(profile);
            ShiftTerms terms = calendar.effective(person, day).terms();
            if (calendar.holiday(person, day).isPresent() || terms.isWeeklyOff(day)) {
                continue;
            }
            groups.computeIfAbsent(
                            terms.shiftCode(), code -> new Group(terms, calendar.times(terms, day), new ArrayList<>()))
                    .employees()
                    .add(profile.id());
        }
        return groups;
    }

    private RunView close(Group group, LocalDate day, long shiftId, Trigger trigger, AuditActor actor) {
        Map<UUID, DayResult> results = attendance.recomputeDay(group.employees(), day);
        int missing = 0;
        int finalised = 0;
        int absent = 0;
        for (DayResult result : results.values()) {
            var timeline = result.timeline();
            missing += timeline.missingCheckOut() ? 1 : 0;
            finalised += timeline.checkIn() != null || timeline.checkOut() != null ? 1 : 0;
            absent += result.status() == AttendanceStatus.ABSENT ? 1 : 0;
        }
        ShiftTerms terms = group.terms();
        String code = "DC-" + day + "-" + terms.shiftCode();
        Instant ranAt = time.now();
        boolean stored = store.insert(new DayCloseStore.NewRun(
                code,
                shiftId,
                day,
                ranAt,
                trigger.name(),
                actor.employeeId(),
                results.size(),
                finalised,
                absent,
                missing));
        if (!stored) {
            return null;
        }
        String target = terms.shiftName() + " · " + TimeFormat.date(day);
        audit.record(
                actor,
                AuditCategory.SYSTEM,
                "Day close finished",
                target,
                null,
                finalised + " finalised · " + missing + " missing check-outs · " + absent + " absent",
                Map.of("run", code, "employees", results.size()));
        if (missing > 0) {
            notifications.publish(
                    "NR-08",
                    new NotificationRequest(
                            "Day close: " + missing + (missing == 1 ? " missing check-out" : " missing check-outs"),
                            terms.shiftName() + " shift on " + TimeFormat.date(day) + ". HRMS regularises these.",
                            "/attendance/log?day=" + day,
                            "NR-08:" + code,
                            Map.of("shift", terms.shiftName(), "day", day.toString(), "missing", missing)));
        }
        return new RunView(
                code,
                terms.shiftCode(),
                terms.shiftName(),
                day,
                ranAt,
                trigger.name(),
                actor.label(),
                results.size(),
                finalised,
                absent,
                missing);
    }
}
