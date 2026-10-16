package com.krztechnologies.attend.attendance.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.undo.UndoTokens;
import com.krztechnologies.attend.crossing.service.CrossingCorrections;
import com.krztechnologies.attend.crossing.service.CrossingCorrections.Kind;
import com.krztechnologies.attend.hrms.service.HrmsOutboxReconciler;
import com.krztechnologies.attend.rules.service.Direction;
import com.krztechnologies.attend.rules.service.PunchRulesEngine;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.Crossing;
import com.krztechnologies.attend.rules.service.PunchRulesEngine.DayResult;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections (R11, attendance.edit): void a punch (wrong person) or flip its direction, with a before/after preview
 * from the same engine, and undo within the undo window. Only identified crossings inside the retention window can be
 * corrected; unidentified ones are resolved in the Review Queue. Each change and undo is audited (Attendance) and the
 * day is recomputed, which voids and re-sends HRMS punches as needed (R8).
 */
@Service
public class CorrectionService {

    private static final String UNDO_KIND = "correction";

    private final CrossingCorrections crossings;
    private final AttendanceRecomputeService attendance;
    private final HrmsOutboxReconciler outbox;
    private final UndoTokens undo;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final SettingsService settings;
    private final TimeService time;

    public CorrectionService(
            CrossingCorrections crossings,
            AttendanceRecomputeService attendance,
            HrmsOutboxReconciler outbox,
            UndoTokens undo,
            AuditService audit,
            CurrentUser currentUser,
            SettingsService settings,
            TimeService time) {
        this.crossings = crossings;
        this.attendance = attendance;
        this.outbox = outbox;
        this.undo = undo;
        this.audit = audit;
        this.currentUser = currentUser;
        this.settings = settings;
        this.time = time;
    }

    /** The effect of one crossing of the day. */
    public record EffectRow(String code, String effect, String reason) {}

    /** A day as the dialog shows it ("Before" / "After"). */
    public record DaySummary(
            String status,
            String statusCode,
            boolean finalised,
            Instant checkIn,
            Instant checkOut,
            List<EffectRow> effects) {}

    /** @param alreadySent punches of the day were sent to HRMS and will be voided and re-sent */
    public record PreviewView(DaySummary before, DaySummary after, boolean alreadySent) {}

    /** @param undoSeconds how long {@code undoToken} works */
    public record Applied(String undoToken, int undoSeconds) {}

    @Transactional(readOnly = true)
    public PreviewView preview(String code, Kind kind) {
        CrossingCorrections.Target target = correctable(crossings.find(code));
        String by = currentUser.require().displayName();
        var preview = attendance.preview(
                target.employeeId(),
                target.shiftDay(),
                list -> list.stream()
                        .map(c -> c.id() == target.id() ? corrected(c, kind, by, "Preview") : c)
                        .toList());
        return new PreviewView(
                summary(preview.crossings(), preview.before()),
                summary(preview.crossings(), preview.after()),
                outbox.hasSentPunches(target.employeeId(), target.shiftDay()));
    }

    @Transactional
    public Applied apply(String code, Kind kind, String reason) {
        CrossingCorrections.Target target = correctable(crossings.lock(code));
        AttendPrincipal actor = currentUser.require();
        long correction = crossings.apply(target, kind, reason, actor.employeeId());
        Direction after = target.direction() == Direction.IN ? Direction.OUT : Direction.IN;
        if (kind == Kind.VOID) {
            audit.record(
                    AuditCategory.ATTENDANCE,
                    "Voided punch",
                    target(target),
                    target.direction().name() + " " + dateTime(target.occurredAt()),
                    "Voided — " + reason,
                    null);
        } else {
            audit.record(
                    AuditCategory.ATTENDANCE,
                    "Flipped direction",
                    target(target),
                    target.direction().name(),
                    after.name() + " — " + reason,
                    null);
        }
        String token = undo.issue(UNDO_KIND, actor.employeeId(), Map.of("correction", Long.toString(correction)));
        return new Applied(token, undo.windowSeconds());
    }

    @Transactional
    public void undo(String token) {
        AttendPrincipal actor = currentUser.require();
        Map<String, String> data = undo.redeem(UNDO_KIND, actor.employeeId(), token)
                .orElseThrow(() -> new Errors.Conflict("UNDO_EXPIRED", "This can no longer be undone."));
        var undone = crossings
                .undo(Long.parseLong(data.get("correction")), actor.employeeId())
                .orElseThrow(() -> new Errors.Conflict("UNDO_EXPIRED", "This can no longer be undone."));
        audit.record(
                AuditCategory.ATTENDANCE,
                undone.kind() == Kind.VOID ? "Undid void" : "Undid direction flip",
                target(undone.crossing()),
                null,
                null,
                null);
    }

    /** The crossing, if it may be corrected. */
    private CrossingCorrections.Target correctable(Optional<CrossingCorrections.Target> crossing) {
        CrossingCorrections.Target target = crossing.orElseThrow(() -> new Errors.NotFound("Crossing not found."));
        if (target.employeeId() == null || target.shiftDay() == null) {
            throw new Errors.BusinessRule(
                    "NOT_CORRECTABLE", "This crossing has no identified employee. Resolve it in the Review Queue.");
        }
        if (target.voided()) {
            throw new Errors.BusinessRule("NOT_CORRECTABLE", "This punch is voided already.");
        }
        if (time.dayOf(target.occurredAt())
                .isBefore(time.today().minusDays(settings.privacy().eventDays()))) {
            throw new Errors.BusinessRule("NOT_CORRECTABLE", "This crossing is outside the retention window.");
        }
        return target;
    }

    private static Crossing corrected(Crossing c, Kind kind, String by, String reason) {
        return kind == Kind.VOID
                ? new Crossing(
                        c.id(),
                        c.code(),
                        c.ts(),
                        c.direction(),
                        c.method(),
                        c.camera(),
                        c.uTurn(),
                        new PunchRulesEngine.Voided(by, reason))
                : new Crossing(
                        c.id(),
                        c.code(),
                        c.ts(),
                        c.direction() == Direction.IN ? Direction.OUT : Direction.IN,
                        c.method(),
                        c.camera(),
                        c.uTurn(),
                        c.voided());
    }

    /** The day's status and the effect of each of its crossings, in time order. */
    private static DaySummary summary(List<Crossing> crossings, DayResult day) {
        var timeline = day.timeline();
        List<EffectRow> effects = crossings.stream()
                .map(c -> {
                    var effect = timeline.effects().get(c.id());
                    return new EffectRow(c.code(), effect.effect().name(), effect.reason());
                })
                .toList();
        return new DaySummary(
                day.applicable() ? day.status().name() : null,
                day.statusCode(),
                timeline.finalised(),
                timeline.checkIn() == null ? null : timeline.checkIn().ts(),
                timeline.checkOut() == null ? null : timeline.checkOut().ts(),
                effects);
    }

    private static String target(CrossingCorrections.Target t) {
        return t.code() + " · " + t.employeeName();
    }

    private String dateTime(Instant at) {
        return TimeFormat.of(settings.general().timeFormat())
                .dateTime(time.zoned(at).toLocalDateTime());
    }
}
