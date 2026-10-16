package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.access.service.AccessRequestService;
import com.krztechnologies.attend.attendance.service.AttendanceViews;
import com.krztechnologies.attend.attendance.service.DayCloseService;
import com.krztechnologies.attend.attendance.service.TodayService;
import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.camera.service.LiveViewService;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.crossing.service.CrossingService;
import com.krztechnologies.attend.crossing.service.CrossingViews;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.enrolment.service.EnrolmentService;
import com.krztechnologies.attend.enrolment.service.EnrolmentViews;
import com.krztechnologies.attend.hrms.service.HrmsOutboxService;
import com.krztechnologies.attend.organisation.service.HolidayService;
import com.krztechnologies.attend.review.service.ReviewService;
import com.krztechnologies.attend.review.service.ReviewViews;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The Dashboard (the prototype's {@code dashboardBodyHTML}): KPIs, alerts, the camera card, crossings by hour, the
 * newest crossings, the review summary, the enrolment queue and the status rows, each section only for those who may
 * see it.
 */
@Service
public class DashboardService {

    private static final int FIRST_HOUR = 6;
    private static final int HOURS = 18;
    private static final int AVERAGE_DAYS = 10;
    private static final int AVERAGE_LOOKBACK_DAYS = 21;
    private static final int QUEUE_SHOWN = 8;
    private static final int REENROL_NAMES = 3;
    private static final Duration DAY_CLOSE_ALERT_AGE = Duration.ofHours(36);

    private final CurrentUser currentUser;
    private final TodayService today;
    private final ReviewService reviews;
    private final HrmsOutboxService hrms;
    private final LiveViewService cameras;
    private final CrossingService crossings;
    private final EnrolmentService enrolment;
    private final EmployeeDirectory employees;
    private final AccessRequestService accessRequests;
    private final DayCloseService dayClose;
    private final HolidayService holidays;
    private final SystemStatusService status;
    private final SettingsService settings;
    private final TimeService time;

    public DashboardService(
            CurrentUser currentUser,
            TodayService today,
            ReviewService reviews,
            HrmsOutboxService hrms,
            LiveViewService cameras,
            CrossingService crossings,
            EnrolmentService enrolment,
            EmployeeDirectory employees,
            AccessRequestService accessRequests,
            DayCloseService dayClose,
            HolidayService holidays,
            SystemStatusService status,
            SettingsService settings,
            TimeService time) {
        this.currentUser = currentUser;
        this.today = today;
        this.reviews = reviews;
        this.hrms = hrms;
        this.cameras = cameras;
        this.crossings = crossings;
        this.enrolment = enrolment;
        this.employees = employees;
        this.accessRequests = accessRequests;
        this.dayClose = dayClose;
        this.holidays = holidays;
        this.status = status;
        this.settings = settings;
        this.time = time;
    }

    public DashboardViews.Dashboard dashboard() {
        boolean attendance = currentUser.has(Permission.ATTENDANCE_VIEW_ALL);
        boolean review = currentUser.has(Permission.REVIEW_RESOLVE);
        boolean integrations = currentUser.has(Permission.SETTINGS_INTEGRATIONS);
        AttendanceViews.TodayStats stats = attendance ? today.stats() : null;
        ReviewViews.Glance glance = review ? reviews.glance() : null;
        Map<String, Long> outbox = integrations ? hrms.counts() : null;
        List<CameraViews.LiveTile> tiles = cameras.tiles();
        return new DashboardViews.Dashboard(
                stats == null ? null : kpis(stats, glance, outbox),
                alerts(tiles, glance, outbox),
                currentUser.has(Permission.LIVE_VIEW) ? cameraCard(tiles, stats) : null,
                attendance ? chart() : null,
                crossings.recent(null, attendance ? 10 : 6),
                glance == null ? null : glance.open(),
                currentUser.has(Permission.PEOPLE_ENROL) ? enrolmentCard() : null,
                status.summary());
    }

    private DashboardViews.Kpis kpis(
            AttendanceViews.TodayStats stats, ReviewViews.Glance glance, Map<String, Long> outbox) {
        return new DashboardViews.Kpis(
                stats.inside(),
                stats.working(),
                stats.checkedIn(),
                stats.late(),
                stats.notYetIn(),
                stats.absent(),
                settings.attendanceRules().absentCutoffHours(),
                stats.checkedOut(),
                stats.earlyExit(),
                glance == null
                        ? null
                        : glance.open().values().stream()
                                .mapToInt(Integer::intValue)
                                .sum(),
                glance == null ? null : glance.unknownToday(),
                outbox == null ? null : outbox.get("FAILED").intValue());
    }

    /** The prototype's {@code alertsList}, in its order. */
    private List<DashboardViews.Alert> alerts(
            List<CameraViews.LiveTile> tiles, ReviewViews.Glance glance, Map<String, Long> outbox) {
        List<DashboardViews.Alert> alerts = new ArrayList<>();
        String cameraLink = currentUser.has(Permission.SETTINGS_CAMERAS) ? "/settings/cameras" : "/live";
        for (CameraViews.LiveTile tile : tiles) {
            if (tile.offline()) {
                alerts.add(new DashboardViews.Alert(
                        "CAMERA_OFFLINE",
                        "BAD",
                        tile.name() + " camera offline",
                        (tile.statusSince() == null ? "" : "Since " + status.clock(tile.statusSince()) + " — ")
                                + "crossings are being missed",
                        cameraLink,
                        null));
            } else if (tile.connecting()) {
                alerts.add(new DashboardViews.Alert(
                        "CAMERA_RECONNECTING",
                        "WARN",
                        tile.name() + " camera reconnecting",
                        "Reconnecting…",
                        cameraLink,
                        null));
            }
        }
        if (glance != null) {
            int liveness = glance.open().getOrDefault("LIVENESS", 0);
            if (liveness > 0) {
                alerts.add(new DashboardViews.Alert(
                        "LIVENESS_WAITING",
                        "BAD",
                        SystemStatusService.plural(liveness, "liveness alert", "liveness alerts") + " waiting",
                        "Possible photo or phone spoof",
                        "/review?tab=liveness",
                        null));
            }
            if (glance.waiting() > 0) {
                alerts.add(new DashboardViews.Alert(
                        "REVIEW_WAITING",
                        "WARN",
                        SystemStatusService.plural(glance.waiting(), "review item", "review items") + " waiting over "
                                + glance.waitingMinutes() + " min",
                        "Oldest",
                        "/review",
                        glance.oldestWaiting()));
            }
        }
        if (outbox != null) {
            long failed = outbox.get("FAILED");
            if (failed > 0) {
                alerts.add(new DashboardViews.Alert(
                        "HRMS_FAILED",
                        "BAD",
                        SystemStatusService.plural(failed, "HRMS push", "HRMS pushes") + " failed",
                        "Retries exhausted — retry from the outbox",
                        "/settings/hrms",
                        null));
            }
            long blocked = outbox.get("BLOCKED_NO_EMPLOYEE_ID");
            if (blocked > 0) {
                alerts.add(new DashboardViews.Alert(
                        "PUNCHES_BLOCKED",
                        "WARN",
                        SystemStatusService.plural(blocked, "punch", "punches") + " blocked — no Employee ID",
                        String.join(", ", hrms.blockedEmployees()),
                        "/employees?filter=missing-id",
                        null));
            }
        }
        if (currentUser.has(Permission.ATTENDANCE_VIEW_ALL)) {
            missingCheckOuts().ifPresent(alerts::add);
        }
        if (currentUser.has(Permission.SETTINGS_ROLES)) {
            List<String> waiting = accessRequests.pendingNames();
            if (!waiting.isEmpty()) {
                alerts.add(new DashboardViews.Alert(
                        "ACCESS_REQUESTS",
                        "INFO",
                        SystemStatusService.plural(waiting.size(), "access request", "access requests") + " waiting",
                        String.join(", ", waiting),
                        "/settings/users?tab=requests",
                        null));
            }
        }
        if (currentUser.has(Permission.PEOPLE_ENROL)) {
            List<String> reenrol = employees.needingReenrolment();
            if (!reenrol.isEmpty()) {
                alerts.add(new DashboardViews.Alert(
                        "REENROLMENT",
                        "INFO",
                        SystemStatusService.plural(reenrol.size(), "employee", "employees") + " need re-enrolment",
                        String.join(", ", reenrol.subList(0, Math.min(REENROL_NAMES, reenrol.size())))
                                + (reenrol.size() > REENROL_NAMES ? "…" : ""),
                        "/enrol",
                        null));
            }
        }
        return alerts;
    }

    /** The newest day close of the last 36 h that left check-outs missing. */
    private Optional<DashboardViews.Alert> missingCheckOuts() {
        Instant since = time.now().minus(DAY_CLOSE_ALERT_AGE);
        return dayClose.runs(0, 50).items().stream()
                .filter(run -> run.missingCheckOuts() > 0 && run.ranAt().isAfter(since))
                .findFirst()
                .map(run -> new DashboardViews.Alert(
                        "MISSING_CHECK_OUTS",
                        "WARN",
                        SystemStatusService.plural(run.missingCheckOuts(), "missing check-out", "missing check-outs")
                                + " at day close",
                        run.shiftName() + " · " + TimeFormat.date(run.shiftDay()) + " — HRMS regularises these",
                        "/attendance/log?day=" + run.shiftDay(),
                        null));
    }

    private DashboardViews.CameraCard cameraCard(List<CameraViews.LiveTile> tiles, AttendanceViews.TodayStats stats) {
        if (tiles.isEmpty()) {
            return null;
        }
        CameraViews.LiveTile first = tiles.getFirst();
        List<CrossingViews.EventRow> last = crossings.recent(first.code(), 1);
        return new DashboardViews.CameraCard(
                first,
                stats == null ? null : stats.inside(),
                last.isEmpty() ? null : last.getFirst().occurredAt());
    }

    /** Today's identified crossings per hour against the average of up to 10 working days of the last 3 weeks. */
    private DashboardViews.HourlyChart chart() {
        LocalDate day = time.today();
        LocalDate first = day.minusDays(AVERAGE_LOOKBACK_DAYS - 1L);
        Set<LocalDate> publicHolidays = holidays.organisationWidePublicHolidays(first, day);
        List<LocalDate> previous = new ArrayList<>();
        for (LocalDate d = day.minusDays(1); previous.size() < AVERAGE_DAYS && d.isAfter(first); d = d.minusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend && !publicHolidays.contains(d)) {
                previous.add(d);
            }
        }
        int[] inToday = new int[HOURS];
        int[] outToday = new int[HOURS];
        double[] inSum = new double[HOURS];
        double[] outSum = new double[HOURS];
        for (CrossingViews.HourCount count : crossings.hourly(first, day)) {
            int index = count.hour() - FIRST_HOUR;
            if (index < 0 || index >= HOURS) {
                continue;
            }
            boolean in = "IN".equals(count.direction());
            if (count.day().equals(day)) {
                (in ? inToday : outToday)[index] += count.count();
            } else if (previous.contains(count.day())) {
                (in ? inSum : outSum)[index] += count.count();
            }
        }
        return new DashboardViews.HourlyChart(
                FIRST_HOUR,
                list(inToday),
                list(outToday),
                average(inSum, previous.size()),
                average(outSum, previous.size()),
                previous.size());
    }

    private DashboardViews.EnrolmentCard enrolmentCard() {
        List<EnrolmentViews.QueueRow> queue = enrolment.queue().people();
        return new DashboardViews.EnrolmentCard(
                queue.subList(0, Math.min(QUEUE_SHOWN, queue.size())),
                queue.size(),
                employees.enrolledSince(time.now().minus(Duration.ofDays(7))));
    }

    private static List<Integer> list(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return Collections.unmodifiableList(list);
    }

    /** One decimal, like the prototype's {@code toFixed(1)}; zeros without days to average. */
    private static List<Double> average(double[] sums, int days) {
        List<Double> list = new ArrayList<>(sums.length);
        for (double sum : sums) {
            list.add(days == 0 ? 0.0 : Math.round(sum / days * 10) / 10.0);
        }
        return Collections.unmodifiableList(list);
    }
}
