package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.attendance.repository.AttendanceQueries;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.crossing.service.CrossingTimelines;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.Direction;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.Crossing;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.DayResult;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.PunchEffect;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The day drawer: one employee, one shift day, every rule outcome. Evaluated now with the same engine as the stored
 * day (nothing is stored here), with the shift's markers, each crossing's effect and reason, and the HRMS punches.
 */
@Service
public class DayDetailService {

    private final EmployeeDirectory employees;
    private final OrganisationDirectory organisation;
    private final AttendanceRecomputeService attendance;
    private final CrossingTimelines timelines;
    private final AttendanceQueries queries;
    private final SettingsService settings;

    public DayDetailService(
            EmployeeDirectory employees,
            OrganisationDirectory organisation,
            AttendanceRecomputeService attendance,
            CrossingTimelines timelines,
            AttendanceQueries queries,
            SettingsService settings) {
        this.employees = employees;
        this.organisation = organisation;
        this.attendance = attendance;
        this.timelines = timelines;
        this.queries = queries;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public AttendanceViews.DayDetail day(UUID employeeId, LocalDate day) {
        var profile = employees.attendanceProfiles(List.of(employeeId)).get(employeeId);
        if (profile == null) {
            throw new Errors.NotFound("Employee not found.");
        }
        var evaluated = attendance.preview(employeeId, day, UnaryOperator.identity());
        DayResult r = evaluated.before();
        var timeline = r.timeline();
        ShiftCalendar.Effective effective = r.effective();
        ShiftTerms terms = effective.terms();
        ShiftCalendar.Times t = r.times();
        Map<Long, Direction> flipped = timelines.flippedFrom(employeeId, day);
        List<AttendanceViews.DayCrossing> crossings = evaluated.crossings().stream()
                .map(c -> crossing(c, timeline.effectOf(c), flipped.get(c.id())))
                .toList();
        List<AttendanceViews.Marker> markers = List.of(
                new AttendanceViews.Marker(t.windowStart(), "Check-in window opens"),
                new AttendanceViews.Marker(t.start(), "Shift starts (" + terms.shiftName() + ")"),
                new AttendanceViews.Marker(
                        terms.lateAfter(t), "Late after this (" + terms.lateGraceMinutes() + " min grace)"),
                new AttendanceViews.Marker(
                        terms.earlyLimit(t), "Early exit before this (" + terms.earlyGraceMinutes() + " min grace)"),
                new AttendanceViews.Marker(t.end(), "Shift ends"),
                new AttendanceViews.Marker(t.close(), "Day close — check-out becomes final"));
        List<AttendanceViews.DayPunch> punches = queries.punches(employeeId, day).stream()
                .map(p -> new AttendanceViews.DayPunch(
                        p.punchId(),
                        p.kind(),
                        p.type(),
                        p.timestamp() == null
                                ? null
                                : OffsetDateTime.parse(p.timestamp()).toInstant(),
                        p.status()))
                .toList();
        boolean applicable = r.applicable();
        return new AttendanceViews.DayDetail(
                AttendanceReads.persons(queries.people(List.of(employeeId))).get(employeeId),
                day,
                applicable ? r.status().name() : null,
                r.statusCode(),
                timeline.finalised(),
                applicable && timeline.late(),
                applicable ? timeline.lateMinutes() : null,
                timeline.checkIn() == null ? null : timeline.checkIn().ts(),
                timeline.checkOut() == null ? null : timeline.checkOut().ts(),
                timeline.checkOutFinal(),
                applicable && timeline.missingCheckOut(),
                applicable ? timeline.workedMinutes() : null,
                settings.attendanceRules().workedHours().name(),
                AttendanceReads.shift(terms),
                organisation.sourceText(effective),
                r.holiday() == null ? null : r.holiday().name(),
                r.weeklyOff(),
                r.tracked(),
                profile.trackingLabel(),
                new AttendanceViews.Times(t.windowStart(), t.start(), t.end(), t.cutoff(), t.close()),
                markers,
                crossings,
                punches,
                r.computedAt());
    }

    private static AttendanceViews.DayCrossing crossing(Crossing c, PunchEffect effect, Direction from) {
        return new AttendanceViews.DayCrossing(
                c.code(),
                c.ts(),
                c.direction().name(),
                from == null ? null : from.name(),
                c.method() == null ? null : c.method().name(),
                effect == null ? null : effect.effect().name(),
                effect == null ? null : effect.reason(),
                c.voided() != null);
    }
}
