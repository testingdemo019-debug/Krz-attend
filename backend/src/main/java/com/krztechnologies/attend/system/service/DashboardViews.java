package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.crossing.service.CrossingViews;
import com.krztechnologies.attend.enrolment.service.EnrolmentViews;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The Dashboard: every section is null when the user may not see it (the prototype's {@code dashboardBodyHTML}). */
public final class DashboardViews {

    private DashboardViews() {}

    /**
     * The KPI row (attendance.view.all).
     *
     * @param absentCutoffHours "becomes Absent n h after shift start"
     * @param pendingReview open review items, null without review.resolve
     * @param unknownFacesToday null without review.resolve
     * @param hrmsFailed null without settings.integrations
     */
    public record Kpis(
            int inside,
            int working,
            int checkedIn,
            int late,
            int notYetIn,
            int absent,
            int absentCutoffHours,
            int checkedOut,
            int earlyExit,
            @Nullable Integer pendingReview,
            @Nullable Integer unknownFacesToday,
            @Nullable Integer hrmsFailed) {}

    /**
     * One alert (the prototype's {@code alertsList}).
     *
     * @param kind what it is about, for the icon: CAMERA_OFFLINE, CAMERA_RECONNECTING, LIVENESS_WAITING,
     *     REVIEW_WAITING, HRMS_FAILED, PUNCHES_BLOCKED, MISSING_CHECK_OUTS, ACCESS_REQUESTS, REENROLMENT
     * @param level BAD, WARN or INFO
     * @param at a time the sub-line is relative to ("Oldest 42 min ago"), else null
     */
    public record Alert(
            String kind,
            String level,
            String title,
            String sub,
            String link,
            @Nullable Instant at) {}

    /**
     * The camera card (live.view): the first camera.
     *
     * @param insideNow null without attendance.view.all
     */
    public record CameraCard(
            CameraViews.LiveTile camera,
            @Nullable Integer insideNow,
            @Nullable Instant lastCrossingAt) {}

    /**
     * Crossings by hour, 6 AM to midnight IST (attendance.view.all): today and the average of the last working days.
     *
     * @param firstHour the hour of index 0
     * @param averageDays how many working days the averages cover (up to 10 within 21 days)
     */
    public record HourlyChart(
            int firstHour,
            List<Integer> inToday,
            List<Integer> outToday,
            List<Double> inAverage,
            List<Double> outAverage,
            int averageDays) {}

    /** The enrolment card (people.enrol): who waits (the first 8), how many, and enrolments of the last 7 days. */
    public record EnrolmentCard(List<EnrolmentViews.QueueRow> waiting, int total, int enrolledLast7Days) {}

    /**
     * @param recent the newest crossings: 10 with attendance.view.all, else 6
     * @param review open items per type (review.resolve)
     * @param health the status summary, as for the topbar pill
     */
    public record Dashboard(
            @Nullable Kpis kpis,
            List<Alert> alerts,
            @Nullable CameraCard camera,
            @Nullable HourlyChart chart,
            List<CrossingViews.EventRow> recent,
            @Nullable Map<String, Integer> review,
            @Nullable EnrolmentCard enrolment,
            SystemStatusService.Summary health) {}
}
