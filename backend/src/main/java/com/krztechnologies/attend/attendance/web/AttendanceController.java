package com.krztechnologies.attend.attendance.web;

import com.krztechnologies.attend.attendance.service.AttendanceLogService;
import com.krztechnologies.attend.attendance.service.AttendanceViews;
import com.krztechnologies.attend.attendance.service.DayDetailService;
import com.krztechnologies.attend.attendance.service.TodayService;
import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.rules.service.AttendanceStatus;
import com.krztechnologies.attend.rules.service.OutboxStatus;
import com.krztechnologies.attend.rules.service.TimeFormat;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Today (rows, KPIs, evacuation list), the attendance log (grid, CSV) and the day drawer. */
@RestController
@RequestMapping("/api/v1/attendance")
@Validated
public class AttendanceController {

    private static final String QUICK =
            "all|inside|checked-in|late|not-in|stepped-out|checked-out|early-exit|absent|missing|off|not-tracked";
    private static final String STATUS = "INSIDE|STEPPED_OUT|CHECKED_OUT|LATE|EARLY_EXIT|HALF_DAY|MISSING_CHECK_OUT"
            + "|MISSING_CHECK_IN|NOT_YET_IN|ABSENT|HOLIDAY|WEEKLY_OFF|WORKED_ON_HOLIDAY|WORKED_ON_WEEKLY_OFF|NOT_TRACKED";
    private static final String CODE = "^[A-Z0-9-]{2,20}$";
    private static final String TYPES = "EMPLOYEE|CONTRACTOR|INTERN";

    private final TodayService today;
    private final AttendanceLogService log;
    private final DayDetailService days;
    private final CsvExportService csv;
    private final SettingsService settings;
    private final TimeService time;

    public AttendanceController(
            TodayService today,
            AttendanceLogService log,
            DayDetailService days,
            CsvExportService csv,
            SettingsService settings,
            TimeService time) {
        this.today = today;
        this.log = log;
        this.days = days;
        this.csv = csv;
        this.settings = settings;
        this.time = time;
    }

    @Operation(summary = "Today: every employee on their current shift day, with Now and HRMS columns")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/today")
    public PageResponse<AttendanceViews.TodayRow> today(
            @RequestParam(required = false) @Pattern(regexp = QUICK) String filter,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> department,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> location,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> shift,
            @RequestParam(required = false) @Size(max = 15) List<@Pattern(regexp = STATUS) String> status,
            @RequestParam(required = false) @Size(max = 3) List<@Pattern(regexp = TYPES) String> type,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return today.list(
                new TodayService.Filter(filter, department, location, shift, status, type, q), page, size, sort);
    }

    @Operation(summary = "Today's filter options with their counts among the rows of the quick filter")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/today/facets")
    public AttendanceViews.TodayFacets todayFacets(
            @RequestParam(required = false) @Pattern(regexp = QUICK) String filter) {
        return today.facets(filter);
    }

    @Operation(summary = "Today's rows of the filter as CSV, in the table's order")
    @RequiresPermission({Permission.ATTENDANCE_VIEW_ALL, Permission.EVENTS_EXPORT})
    @GetMapping("/today/export")
    public ResponseEntity<StreamingResponseBody> todayExport(
            @RequestParam(required = false) @Pattern(regexp = QUICK) String filter,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> department,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> location,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> shift,
            @RequestParam(required = false) @Size(max = 15) List<@Pattern(regexp = STATUS) String> status,
            @RequestParam(required = false) @Size(max = 3) List<@Pattern(regexp = TYPES) String> type,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        TimeFormat clock = clock();
        List<Column<AttendanceViews.TodayRow>> columns = List.of(
                new Column<>("Employee", r -> r.employee().name()),
                new Column<>("Employee ID", r -> r.employee().employeeId()),
                new Column<>(
                        "Department",
                        r -> r.employee().department() == null
                                ? null
                                : r.employee().department().name()),
                new Column<>("Shift", r -> r.shift().name() + " " + r.shift().timing()),
                new Column<>("Status", r -> AttendanceStatus.valueOf(r.status()).label()),
                new Column<>("Check-in", r -> time(clock, r.checkIn())),
                new Column<>(
                        "Last movement",
                        r -> r.lastMovement() == null ? null : r.lastDirection() + " " + time(clock, r.lastMovement())),
                new Column<>(
                        "Check-out",
                        r -> r.checkOut() == null
                                ? null
                                : time(clock, r.checkOut()) + (r.checkOutFinal() ? "" : " (tentative)")),
                new Column<>("Now", r -> r.now() == null ? null : "INSIDE".equals(r.now()) ? "Inside" : "Outside"),
                new Column<>("Worked", r -> worked(r.workedMinutes())),
                new Column<>(
                        "HRMS",
                        r -> r.hrmsStatus() == null
                                ? null
                                : OutboxStatus.valueOf(r.hrmsStatus()).label()),
                new Column<>("Tracking", AttendanceViews.TodayRow::trackingLabel));
        var rows = today.export(new TodayService.Filter(filter, department, location, shift, status, type, q), sort);
        return csv.export("today", "Today", columns, rows);
    }

    @Operation(summary = "Today's KPI tiles and the count of every quick filter")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/today/stats")
    public AttendanceViews.TodayStats todayStats() {
        return today.stats();
    }

    @Operation(summary = "Who is inside now, by name (opening it is audited)")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/evacuation")
    public AttendanceViews.Evacuation evacuation() {
        return today.evacuation();
    }

    @Operation(summary = "The evacuation list as CSV")
    @RequiresPermission({Permission.ATTENDANCE_VIEW_ALL, Permission.EVENTS_EXPORT})
    @GetMapping("/evacuation/export")
    public ResponseEntity<StreamingResponseBody> evacuationExport() {
        TimeFormat clock = clock();
        List<Column<AttendanceViews.EvacuationRow>> columns = List.of(
                new Column<>("Name", AttendanceViews.EvacuationRow::name),
                new Column<>("Employee ID", AttendanceViews.EvacuationRow::employeeId),
                new Column<>("Department", AttendanceViews.EvacuationRow::department),
                new Column<>("Last IN (IST)", r -> time(clock, r.lastIn())));
        return csv.export(
                "evacuation-list",
                "Evacuation list",
                columns,
                today.evacuation().people());
    }

    @Operation(summary = "The attendance log: one row per employee, one status code per shift day (up to 31 days)")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/log")
    public PageResponse<AttendanceViews.LogRow> log(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> department,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> shift,
            @RequestParam(required = false) @Size(max = 15) List<@Pattern(regexp = STATUS) String> status,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(defaultValue = "false") boolean includeNotTracked,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return log.log(
                new AttendanceLogService.Filter(from, to, department, shift, status, employeeId, includeNotTracked, q),
                page,
                size,
                sort);
    }

    @Operation(summary = "The log's filter options with their counts among the employees of the range")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/log/facets")
    public AttendanceViews.LogFacets logFacets(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeNotTracked) {
        return log.facets(from, to, includeNotTracked);
    }

    @Operation(summary = "The log as CSV: the grid of codes, or one line per employee-day (detail=daily)")
    @RequiresPermission({Permission.ATTENDANCE_VIEW_ALL, Permission.EVENTS_EXPORT})
    @GetMapping("/log/export")
    public ResponseEntity<StreamingResponseBody> logExport(
            @RequestParam(defaultValue = "grid") @Pattern(regexp = "grid|daily") String detail,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> department,
            @RequestParam(required = false) @Size(max = 100) List<@Pattern(regexp = CODE) String> shift,
            @RequestParam(required = false) @Size(max = 15) List<@Pattern(regexp = STATUS) String> status,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(defaultValue = "false") boolean includeNotTracked,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        var filter =
                new AttendanceLogService.Filter(from, to, department, shift, status, employeeId, includeNotTracked, q);
        TimeFormat clock = clock();
        if ("daily".equals(detail)) {
            List<Column<AttendanceViews.DailyRow>> columns = List.of(
                    new Column<>("Employee ID", r -> r.employee().employeeId()),
                    new Column<>("Name", r -> r.employee().name()),
                    new Column<>(
                            "Department",
                            r -> r.employee().department() == null
                                    ? null
                                    : r.employee().department().name()),
                    new Column<>("Shift day", AttendanceViews.DailyRow::day),
                    new Column<>("Shift", AttendanceViews.DailyRow::shiftName),
                    new Column<>(
                            "Status", r -> AttendanceStatus.valueOf(r.status()).label()),
                    new Column<>("Check-in", r -> time(clock, r.checkIn())),
                    new Column<>("Check-out", r -> time(clock, r.checkOut())),
                    new Column<>("Worked", r -> worked(r.workedMinutes())),
                    new Column<>("Late", r -> r.late() ? "Yes" : "No"),
                    new Column<>("Early exit", r -> r.earlyExit() ? "Yes" : "No"),
                    new Column<>("Final", r -> r.finalised() ? "Final" : "Tentative"));
            return csv.export("attendance", "Attendance log (daily detail)", columns, log.daily(filter));
        }
        List<AttendanceViews.LogRow> rows = log.all(filter, sort);
        List<Column<AttendanceViews.LogRow>> columns = new ArrayList<>();
        columns.add(new Column<>("Employee", r -> r.employee().name()));
        columns.add(new Column<>("Employee ID", r -> r.employee().employeeId()));
        if (!rows.isEmpty()) {
            for (int i = 0; i < rows.getFirst().days().size(); i++) {
                int index = i;
                LocalDate day = rows.getFirst().days().get(i).day();
                String header = day.getDayOfWeek()
                                .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                                .substring(0, 2)
                        + " " + day.getDayOfMonth();
                columns.add(new Column<>(header, r -> r.days().get(index).statusCode()));
            }
        }
        columns.add(new Column<>("Present", r -> r.totals().present()));
        columns.add(new Column<>("Late", r -> r.totals().late()));
        columns.add(new Column<>("Absent", r -> r.totals().absent()));
        columns.add(new Column<>("Missing", r -> r.totals().missing()));
        columns.add(new Column<>("Worked", r -> worked(r.totals().workedMinutes())));
        return csv.export("attendance-grid", "Attendance log (grid)", columns, rows);
    }

    @Operation(summary = "The day drawer: shift and source, times, crossings with effects and reasons, HRMS punches")
    @RequiresPermission(Permission.ATTENDANCE_VIEW_ALL)
    @GetMapping("/days/{employeeId}/{day}")
    public AttendanceViews.DayDetail day(@PathVariable UUID employeeId, @PathVariable LocalDate day) {
        return days.day(employeeId, day);
    }

    private TimeFormat clock() {
        return TimeFormat.of(settings.general().timeFormat());
    }

    private String time(TimeFormat clock, Instant at) {
        return at == null ? null : clock.time(time.zoned(at).toLocalTime());
    }

    private static String worked(Integer minutes) {
        return minutes == null ? null : TimeFormat.duration(Duration.ofMinutes(minutes));
    }
}
