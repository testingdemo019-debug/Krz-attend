package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.settings.AttendanceRulesSettings;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.Direction;
import com.krztechnologies.attend.rules.service.PunchRulesEngine;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Attendance rules → Rule tester ({@code POST /settings/rules/test}): a day of crossings against the rules,
 * saved or the unsaved draft of the page, with the same engine as Today, Events and the HRMS outbox. Nothing is stored.
 */
@Service
public class RuleTesterService {

    private final OrganisationDirectory organisation;
    private final SettingsService settings;
    private final TimeService time;

    public RuleTesterService(OrganisationDirectory organisation, SettingsService settings, TimeService time) {
        this.organisation = organisation;
        this.settings = settings;
        this.time = time;
    }

    /** A crossing of the scenario: a time of the shift day (or of the next day) and a direction. */
    public record Row(LocalTime time, Direction direction, boolean nextDay) {}

    /**
     * @param rules the page's draft rules; null = the saved ones
     * @param afterClose evaluate after day close (final) or right after the last crossing (live)
     */
    public record Scenario(
            String shiftCode,
            boolean holiday,
            boolean afterClose,
            List<Row> rows,
            @Nullable AttendanceRulesSettings rules) {}

    /** The effect of one row, in row order ({@code code} T1, T2, …). */
    public record RowResult(
            String code, @Nullable String effect, @Nullable String reason) {}

    /** A punch that would go to HRMS. */
    public record Punch(String type, Instant at) {}

    /** The engine's verdict on the scenario. */
    public record Result(
            LocalDate day,
            AttendanceViews.ShiftRef shift,
            AttendanceViews.Times times,
            Instant lateAfter,
            Instant earlyLimit,
            Instant evaluatedAt,
            List<RowResult> rows,
            String status,
            String statusCode,
            boolean finalised,
            boolean late,
            @Nullable Integer lateMinutes,
            boolean earlyExit,
            @Nullable Instant checkIn,
            @Nullable Instant checkOut,
            boolean checkOutFinal,
            boolean missingCheckOut,
            @Nullable Integer workedMinutes,
            List<Punch> punches) {}

    @Transactional(readOnly = true)
    public Result test(Scenario scenario) {
        ShiftCalendar calendar = organisation.calendar();
        LocalDate day = PunchRulesEngine.testerDay(time.today());
        ShiftTerms terms;
        try {
            terms = calendar.shift(scenario.shiftCode()).on(day);
        } catch (IllegalArgumentException e) {
            throw new Errors.BusinessRule(
                    "VALIDATION",
                    "Choose a shift.",
                    List.of(new ErrorResponse.FieldError("shiftCode", "Choose a shift.")));
        }
        AttendanceRulesSettings rules = scenario.rules() == null ? settings.attendanceRules() : scenario.rules();
        var engine = new PunchRulesEngine(
                rules, time.zone(), TimeFormat.of(settings.general().timeFormat()));
        var run = engine.test(
                terms,
                day,
                scenario.holiday(),
                scenario.afterClose(),
                scenario.rows().stream()
                        .map(r -> new PunchRulesEngine.TestRow(r.time(), r.direction(), r.nextDay()))
                        .toList());
        var timeline = run.timeline();
        ShiftCalendar.Times t = run.times();
        return new Result(
                day,
                AttendanceReads.shift(terms),
                new AttendanceViews.Times(t.windowStart(), t.start(), t.end(), t.cutoff(), t.close()),
                terms.lateAfter(t),
                terms.earlyLimit(t),
                run.evaluatedAt(),
                run.crossings().stream()
                        .map(c -> {
                            var effect = timeline.effectOf(c);
                            return new RowResult(c.code(), effect.effect().name(), effect.reason());
                        })
                        .toList(),
                timeline.status().name(),
                timeline.status().code(),
                timeline.finalised(),
                timeline.late(),
                timeline.lateMinutes(),
                timeline.earlyExit(),
                timeline.checkIn() == null ? null : timeline.checkIn().ts(),
                timeline.checkOut() == null ? null : timeline.checkOut().ts(),
                timeline.checkOutFinal(),
                timeline.missingCheckOut(),
                timeline.workedMinutes(),
                run.punches().stream()
                        .map(p -> new Punch(p.type().name(), p.crossing().ts()))
                        .toList());
    }
}
