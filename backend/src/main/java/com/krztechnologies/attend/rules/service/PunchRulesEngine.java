package com.krztechnologies.attend.rules.service;

import com.krztechnologies.attend.common.settings.AttendanceRulesSettings;
import com.krztechnologies.attend.common.settings.AttendanceRulesSettings.PushMode;
import com.krztechnologies.attend.common.settings.AttendanceRulesSettings.WorkedHours;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Effective;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Holiday;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Person;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Times;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The punch rules (R5–R8): turns an employee's crossings of one shift day into effects with reasons, the check-in and
 * check-out, worked time, the day status and the punches KRZ HRMS should have. A pure function of its inputs, ported
 * from the prototype's {@code evaluateTimeline}, {@code evaluateDay}, {@code effectForEvent} and
 * {@code desiredPunches}; Today, the attendance log, Events, the rule tester and the HRMS outbox all use it.
 */
public final class PunchRulesEngine {

    /** SPAN mode deducts the shift's break only from a span of at least this long. */
    private static final Duration BREAK_DEDUCTED_FROM = Duration.ofMinutes(300);

    private static final String U_TURN = "U-turn at the tripwire — crossed and came back within a few seconds";
    /** A walk while capturing the person's face at the entry point (D-118). */
    private static final String ENROLMENT_CAPTURE = "Enrolment capture at the entry point — recorded only";

    private static final String TESTER_CAMERA_CODE = "TEST";
    private static final DateTimeFormatter PAYLOAD_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** The camera a crossing was seen by. */
    public record Camera(String code, String name, CameraMode mode) {}

    /** A void correction (R11): who voided the punch and why. */
    public record Voided(String by, String reason) {}

    /**
     * One identified crossing of the employee's shift day.
     *
     * @param id the crossing's database id (keys the effects)
     * @param code EV-100231
     * @param direction after any flip correction
     * @param voided null unless voided
     * @param enrolmentCapture a walk while the person's face was being captured at the entry point (D-118)
     */
    public record Crossing(
            long id,
            String code,
            Instant ts,
            Direction direction,
            CrossingMethod method,
            Camera camera,
            boolean uTurn,
            Voided voided,
            boolean enrolmentCapture) {

        public Crossing {
            Objects.requireNonNull(ts);
            Objects.requireNonNull(direction);
            Objects.requireNonNull(camera);
        }

        /** A crossing outside an enrolment capture. */
        public Crossing(
                long id,
                String code,
                Instant ts,
                Direction direction,
                CrossingMethod method,
                Camera camera,
                boolean uTurn,
                Voided voided) {
            this(id, code, ts, direction, method, camera, uTurn, voided, false);
        }
    }

    /** The effect of one crossing and the human-readable reason. */
    public record PunchEffect(Effect effect, String reason) {}

    /**
     * The day around the timeline (R6 {@code ctx} without the rules, which the engine holds).
     *
     * @param now the evaluation moment: decides finalisation, the open interval and the absent cutoff
     */
    public record DayContext(Instant now, boolean tracked, boolean holiday, boolean weeklyOff) {}

    /**
     * The evaluated timeline of one shift day (R6).
     *
     * @param effects the effect of every crossing, in time order, keyed by crossing id
     * @param checkOut tentative until {@code checkOutFinal}
     * @param steppedOut left before shift end, not back yet, day not closed
     * @param lastMovement the last counted crossing
     * @param worked null without a check-in or with a missing check-out
     * @param counted the counted crossings ({@code seq}), in time order
     */
    public record Timeline(
            Map<Long, PunchEffect> effects,
            Crossing checkIn,
            Crossing checkOut,
            boolean checkOutFinal,
            boolean inside,
            boolean steppedOut,
            Crossing lastMovement,
            Duration worked,
            AttendanceStatus status,
            boolean late,
            boolean earlyExit,
            boolean missingCheckOut,
            boolean finalised,
            List<Crossing> counted,
            ShiftTerms shift,
            Times times) {

        public PunchEffect effectOf(Crossing crossing) {
            return effects.get(crossing.id());
        }

        /** Worked time in whole minutes (rounded), or null. */
        public Integer workedMinutes() {
            return worked == null ? null : (int) TimeFormat.roundedMinutes(worked);
        }

        /** Minutes from shift start to the check-in when late, otherwise null. */
        public Integer lateMinutes() {
            return late ? (int) TimeFormat.roundedMinutes(Duration.between(times.start(), checkIn.ts())) : null;
        }
    }

    /**
     * What R5 needs to know about an employee's employment.
     *
     * @param joinDate null while the employment details are missing (never tracked, never applicable)
     * @param leftOn the last day of a disabled account, or null
     * @param trackedMode tracking mode {@code TRACKED}
     * @param notTrackedReason the tracking reason's label ("No face profile", "Location without a camera",
     *     "Exempt") when the mode is {@code NOT_TRACKED}; null reads as "Exempt", as in the prototype
     */
    public record Employment(
            LocalDate joinDate,
            LocalDate leftOn,
            boolean accountEnabled,
            boolean trackedMode,
            String notTrackedReason) {

        /** R5: tracked on {@code day}. */
        public boolean trackedOn(LocalDate day) {
            return trackedMode && employedSince(day) && (accountEnabled || (leftOn != null && !day.isAfter(leftOn)));
        }

        /** R6 step 9: false before the join date and after {@code leftOn}. */
        public boolean employedOn(LocalDate day) {
            return employedSince(day) && (leftOn == null || !day.isAfter(leftOn));
        }

        private boolean employedSince(LocalDate day) {
            return joinDate != null && !joinDate.isAfter(day);
        }

        /** Why the employee is not tracked on {@code day}, as shown after "Not tracked — ". */
        String notTrackedText(LocalDate day) {
            if (!trackedMode) {
                return notTrackedReason == null ? "Exempt" : notTrackedReason;
            }
            if (joinDate == null) {
                return "employment details missing";
            }
            return joinDate.isAfter(day) ? "not joined yet" : "account disabled";
        }
    }

    /**
     * One evaluated employee-day: the materialised attendance row (R7).
     *
     * @param holiday the public holiday of the day, or null
     * @param applicable false for a future day, a day before the join date or after {@code leftOn} (shown blank)
     * @param computedAt the evaluation moment
     */
    public record DayResult(
            LocalDate day,
            Effective effective,
            Times times,
            boolean tracked,
            Holiday holiday,
            boolean weeklyOff,
            boolean applicable,
            Timeline timeline,
            Instant computedAt) {

        /** The status, or null when the day is not applicable. */
        public AttendanceStatus status() {
            return applicable ? timeline.status() : null;
        }

        public String statusCode() {
            return applicable ? timeline.status().code() : null;
        }
    }

    /**
     * A punch KRZ HRMS should have (R8).
     *
     * @param outSuffix the {@code -OUT} punch of {@code EVERY} mode
     * @param dueAt a check-in is due at the crossing; a check-out at {@code max(crossing, day close)}
     */
    public record DesiredPunch(PunchType type, Crossing crossing, boolean outSuffix, Instant dueAt) {

        /**
         * {@code PU-<crossing digits>[-OUT]}: stable, so a resend never creates a duplicate. Rule tester rows are not
         * events: their punches are {@code PU-T1}, {@code PU-T2}…
         */
        public String punchId() {
            String code = crossing.code();
            return "PU-" + (code.startsWith("EV-") ? code.substring(3) : code) + (outSuffix ? "-OUT" : "");
        }

        /**
         * The payload sent to HRMS. The JSON also carries {@code final: true}.
         *
         * @param hrmsEmployeeId the employee's HRMS Employee ID, or null (the punch is then blocked)
         */
        public PunchPayload payload(String hrmsEmployeeId, ZoneId zone) {
            return new PunchPayload(
                    punchId(),
                    hrmsEmployeeId,
                    PAYLOAD_TIMESTAMP.format(crossing.ts().atZone(zone)),
                    crossing.direction(),
                    type,
                    crossing.method(),
                    crossing.camera().code(),
                    crossing.code());
        }
    }

    /**
     * The HRMS punch payload (R8) without the constant {@code final: true}.
     *
     * @param employeeId the HRMS Employee ID (EMP-1234), or null
     * @param timestamp ISO-8601 with the offset, e.g. {@code 2026-10-12T09:20:00+05:30}
     * @param deviceId the camera code
     * @param eventId the crossing code
     */
    public record PunchPayload(
            String punchId,
            String employeeId,
            String timestamp,
            Direction direction,
            PunchType type,
            CrossingMethod method,
            String deviceId,
            String eventId) {}

    /** One crossing row of the rule tester: a time of the shift day (or of the next day) and a direction. */
    public record TestRow(LocalTime time, Direction direction, boolean nextDay) {}

    /**
     * The rule tester's result: the same engine on scenario input, nothing stored.
     *
     * @param evaluatedAt one minute after day close, or one minute after the last crossing (live)
     * @param crossings the rows as crossings, in row order (ids 1…n, codes T1…Tn)
     * @param punches what would be sent to HRMS
     */
    public record TestRun(
            LocalDate day,
            Times times,
            Instant evaluatedAt,
            List<Crossing> crossings,
            Timeline timeline,
            List<DesiredPunch> punches) {

        /** The effect of every row, in row order. */
        public List<PunchEffect> effects() {
            return crossings.stream().map(timeline::effectOf).toList();
        }
    }

    private final AttendanceRulesSettings rules;
    private final ZoneId zone;
    private final TimeFormat format;

    /**
     * @param rules Settings → Attendance rules (saved, or a draft in the rule tester)
     * @param zone the organisation's time zone (the shift calendar's)
     * @param format the clock for times inside reasons (Settings → General → time format)
     */
    public PunchRulesEngine(AttendanceRulesSettings rules, ZoneId zone, TimeFormat format) {
        this.rules = Objects.requireNonNull(rules);
        this.zone = Objects.requireNonNull(zone);
        this.format = Objects.requireNonNull(format);
    }

    /** R6: the effects, check-in/out, worked time and status of one shift day's crossings. */
    public Timeline evaluateTimeline(List<Crossing> crossings, ShiftTerms shift, Times times, DayContext ctx) {
        return evaluate(crossings, shift, times, ctx, "");
    }

    /**
     * R5–R7 for one employee-day: the effective shift and its times, holiday, weekly off, tracking, the timeline and
     * whether the day applies. The reasons of a day that is not tracked end with " · Not tracked — … — recorded only,
     * not sent to HRMS".
     *
     * @param crossings the employee's identified crossings of this shift day
     */
    public DayResult evaluateDay(
            ShiftCalendar calendar,
            Person person,
            Employment employment,
            LocalDate day,
            List<Crossing> crossings,
            Instant now) {
        if (!calendar.zone().equals(zone)) {
            throw new IllegalArgumentException("The calendar is in " + calendar.zone() + ", the engine in " + zone);
        }
        Effective effective = calendar.effective(person, day);
        Times times = calendar.times(effective.terms(), day);
        Holiday holiday = calendar.holiday(person, day).orElse(null);
        boolean weeklyOff = effective.terms().isWeeklyOff(day);
        boolean tracked = employment.trackedOn(day);
        String suffix = tracked
                ? ""
                : " · Not tracked — " + employment.notTrackedText(day) + " — recorded only, not sent to HRMS";
        Timeline timeline = evaluate(
                crossings, effective.terms(), times, new DayContext(now, tracked, holiday != null, weeklyOff), suffix);
        boolean applicable = !day.isAfter(calendar.dayOf(now)) && employment.employedOn(day);
        return new DayResult(day, effective, times, tracked, holiday, weeklyOff, applicable, timeline, now);
    }

    /**
     * The effect of a crossing without an identified employee, from its decision (R6).
     *
     * @param reviewCode the open review item (RV-2041) of a pending crossing
     * @param unknownHours privacy setting {@code unknownHours}
     */
    /** The effect of an enrolment capture walk by someone not (yet) recognised: recorded, never a review item. */
    public static PunchEffect enrolmentCapture() {
        return new PunchEffect(Effect.MOVEMENT, ENROLMENT_CAPTURE);
    }

    public static PunchEffect unidentified(boolean uTurn, Decision decision, String reviewCode, int unknownHours) {
        if (uTurn) {
            return new PunchEffect(Effect.IGNORED_TURNED_BACK, U_TURN);
        }
        return switch (decision) {
            case PENDING_REVIEW ->
                new PunchEffect(Effect.PENDING_REVIEW, "Waiting in the Review Queue (" + reviewCode + ")");
            case NOT_AN_EMPLOYEE -> new PunchEffect(Effect.IGNORED_UNKNOWN, "Marked as not an employee");
            case REJECTED -> new PunchEffect(Effect.IGNORED_UNKNOWN, "Rejected in review — bad image");
            case SPOOF_CONFIRMED -> new PunchEffect(Effect.IGNORED_UNKNOWN, "Spoof attempt confirmed — no punch");
            case EXPIRED_NO_ACTION ->
                new PunchEffect(Effect.IGNORED_UNKNOWN, "Not resolved within " + unknownHours + " h — crop purged");
            case AUTO, RESOLVED_IN_REVIEW -> new PunchEffect(Effect.IGNORED_UNKNOWN, "Not identified");
        };
    }

    /** R8: the punches HRMS should have for an evaluated day; none unless tracked and applicable. */
    public List<DesiredPunch> desiredPunches(DayResult day) {
        return day.tracked() && day.applicable() ? desiredPunches(day.timeline()) : List.of();
    }

    /**
     * R8 for a timeline. {@code FINAL}: the check-in, plus the check-out once final. {@code EVERY}: every counted
     * crossing ({@code CHECK_IN} for the check-in, else {@code MOVEMENT}), plus the final check-out as {@code -OUT}.
     */
    public List<DesiredPunch> desiredPunches(Timeline timeline) {
        List<DesiredPunch> punches = new ArrayList<>();
        Crossing checkOut = timeline.finalised() ? timeline.checkOut() : null;
        if (rules.pushMode() == PushMode.EVERY) {
            for (Crossing c : timeline.counted()) {
                PunchType type = c.equals(timeline.checkIn()) ? PunchType.CHECK_IN : PunchType.MOVEMENT;
                punches.add(new DesiredPunch(type, c, false, c.ts()));
            }
        } else if (timeline.checkIn() != null) {
            punches.add(new DesiredPunch(
                    PunchType.CHECK_IN,
                    timeline.checkIn(),
                    false,
                    timeline.checkIn().ts()));
        }
        if (checkOut != null) {
            Instant due = max(checkOut.ts(), timeline.times().close());
            punches.add(new DesiredPunch(PunchType.CHECK_OUT, checkOut, rules.pushMode() == PushMode.EVERY, due));
        }
        return List.copyOf(punches);
    }

    /** The rule tester's day: the latest Wednesday on or before {@code today} (a normal working day). */
    public static LocalDate testerDay(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));
    }

    /**
     * The rule tester ({@code POST /settings/rules/test}): runs the engine on scenario rows for a tracked employee on
     * a working day, evaluated after day close or right after the last crossing.
     *
     * @param shift the shift version on {@code day}
     * @param day usually {@link #testerDay(LocalDate)}
     * @param holiday evaluate as a public holiday
     * @param afterClose final (after day close) or live
     */
    public TestRun test(ShiftTerms shift, LocalDate day, boolean holiday, boolean afterClose, List<TestRow> rows) {
        Times times = ShiftCalendar.times(shift, day, zone, Duration.ofHours(rules.absentCutoffHours()));
        Camera camera = new Camera(TESTER_CAMERA_CODE, "Rule tester", CameraMode.BOTH);
        List<Crossing> crossings = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            TestRow row = rows.get(i);
            Instant ts = (row.nextDay() ? day.plusDays(1) : day)
                    .atTime(row.time())
                    .atZone(zone)
                    .toInstant();
            CrossingMethod method = row.direction() == Direction.IN ? CrossingMethod.FACE : CrossingMethod.REID;
            crossings.add(new Crossing(i + 1L, "T" + (i + 1), ts, row.direction(), method, camera, false, null));
        }
        Instant evaluatedAt = afterClose ? times.close().plusSeconds(60) : liveMoment(crossings, times);
        Timeline timeline =
                evaluateTimeline(crossings, shift, times, new DayContext(evaluatedAt, true, holiday, false));
        return new TestRun(day, times, evaluatedAt, List.copyOf(crossings), timeline, desiredPunches(timeline));
    }

    /** One minute after the last crossing (at least shift start), but before day close. */
    private static Instant liveMoment(List<Crossing> crossings, Times times) {
        Instant last = times.start();
        for (Crossing c : crossings) {
            last = max(last, c.ts());
        }
        return min(times.close().minusSeconds(60), last.plusSeconds(60));
    }

    private Timeline evaluate(List<Crossing> crossings, ShiftTerms shift, Times times, DayContext ctx, String suffix) {
        return new Evaluation(shift, times, ctx, suffix).run(crossings);
    }

    /** One run of R6 steps 1–8 over a shift day's crossings (the prototype's {@code evaluateTimeline}). */
    private final class Evaluation {

        private final ShiftTerms shift;
        private final Times times;
        private final DayContext ctx;
        private final String suffix;
        private final Instant earlyLimit;
        private final Map<Long, PunchEffect> effects = new HashMap<>();
        private Crossing checkIn;
        private Crossing checkOut;
        private Crossing lastCounted;
        private Crossing earlyCandidate;
        private boolean inside;

        Evaluation(ShiftTerms shift, Times times, DayContext ctx, String suffix) {
            this.shift = shift;
            this.times = times;
            this.ctx = ctx;
            this.suffix = suffix;
            this.earlyLimit = shift.earlyLimit(times);
        }

        Timeline run(List<Crossing> crossings) {
            List<Crossing> sorted = crossings.stream()
                    .sorted(Comparator.comparing(Crossing::ts).thenComparingLong(Crossing::id))
                    .toList();
            List<Crossing> seq = withoutTurnedBack(withoutDuplicates(preFilter(sorted)));
            seq.forEach(this::count);

            // 5. Finalisation at day close.
            boolean finalised = !ctx.now().isBefore(times.close());
            boolean checkOutFinal = false;
            boolean missingCheckOut = false;
            if (finalised) {
                if (checkOut != null) {
                    checkOutFinal = true;
                } else if (earlyCandidate != null && !inside) {
                    checkOut = earlyCandidate;
                    checkOutFinal = true;
                    set(
                            earlyCandidate,
                            Effect.CHECK_OUT,
                            "Left before shift end and didn’t come back by day close — early exit");
                } else if (inside && checkIn != null) {
                    missingCheckOut = true;
                }
            }
            boolean earlyExit =
                    checkOut != null && checkOutFinal && checkOut.ts().isBefore(earlyLimit);
            Duration worked = checkIn != null && !missingCheckOut ? worked(seq, finalised) : null;
            boolean late = checkIn != null && checkIn.ts().isAfter(shift.lateAfter(times));

            // 8. Status: the first matching rule wins.
            AttendanceStatus status;
            if (!ctx.tracked()) {
                status = AttendanceStatus.NOT_TRACKED;
            } else if (ctx.holiday()) {
                status = seq.isEmpty() ? AttendanceStatus.HOLIDAY : AttendanceStatus.WORKED_ON_HOLIDAY;
            } else if (ctx.weeklyOff()) {
                status = seq.isEmpty() ? AttendanceStatus.WEEKLY_OFF : AttendanceStatus.WORKED_ON_WEEKLY_OFF;
            } else if (checkIn == null && seq.isEmpty()) {
                status = !finalised && ctx.now().isBefore(times.cutoff())
                        ? AttendanceStatus.NOT_YET_IN
                        : AttendanceStatus.ABSENT;
            } else if (checkIn == null) {
                status = AttendanceStatus.MISSING_CHECK_IN;
            } else if (missingCheckOut) {
                status = AttendanceStatus.MISSING_CHECK_OUT;
            } else if (!finalised) {
                status = inside
                        ? AttendanceStatus.INSIDE
                        : checkOut != null ? AttendanceStatus.CHECKED_OUT : AttendanceStatus.STEPPED_OUT;
            } else if (worked.compareTo(shift.absentBelow()) < 0) {
                status = AttendanceStatus.ABSENT;
            } else if (worked.compareTo(shift.halfDayBelow()) < 0) {
                status = AttendanceStatus.HALF_DAY;
            } else if (earlyExit) {
                status = AttendanceStatus.EARLY_EXIT;
            } else {
                status = late ? AttendanceStatus.LATE : AttendanceStatus.CHECKED_OUT;
            }

            Map<Long, PunchEffect> ordered = new LinkedHashMap<>();
            sorted.forEach(c -> ordered.put(c.id(), effects.get(c.id())));
            return new Timeline(
                    Collections.unmodifiableMap(ordered),
                    checkIn,
                    checkOut,
                    checkOutFinal,
                    inside,
                    !inside && earlyCandidate != null && !finalised,
                    lastCounted,
                    worked,
                    status,
                    late,
                    earlyExit,
                    missingCheckOut,
                    finalised,
                    List.copyOf(seq),
                    shift,
                    times);
        }

        /**
         * 1. Voided, U-turns, enrolment capture walks, directions the camera does not count, and crossings outside the
         * shift day's window.
         */
        private List<Crossing> preFilter(List<Crossing> sorted) {
            List<Crossing> live = new ArrayList<>();
            for (Crossing c : sorted) {
                if (c.voided() != null) {
                    set(
                            c,
                            Effect.VOIDED,
                            "Voided by " + c.voided().by() + ": " + c.voided().reason());
                } else if (c.uTurn()) {
                    set(c, Effect.IGNORED_TURNED_BACK, U_TURN);
                } else if (c.enrolmentCapture()) {
                    set(c, Effect.MOVEMENT, ENROLMENT_CAPTURE);
                } else if (!c.camera().mode().allows(c.direction())) {
                    set(
                            c,
                            Effect.MOVEMENT,
                            "Recorded only — " + c.camera().name() + " is "
                                    + c.camera().mode().label());
                } else if (c.ts().isBefore(times.windowStart())) {
                    set(
                            c,
                            Effect.MOVEMENT,
                            "Before the check-in window (opens " + time(times.windowStart()) + ") — recorded only");
                } else if (!c.ts().isBefore(times.close())) {
                    set(c, Effect.MOVEMENT, "After day close (" + time(times.close()) + ") — recorded only");
                } else {
                    live.add(c);
                }
            }
            return live;
        }

        /** 2. The same direction as the previous kept crossing within {@code duplicateMinutes} (inclusive). */
        private List<Crossing> withoutDuplicates(List<Crossing> live) {
            Duration window = Duration.ofMinutes(rules.duplicateMinutes());
            List<Crossing> kept = new ArrayList<>();
            for (Crossing c : live) {
                Crossing prev = kept.isEmpty() ? null : kept.getLast();
                if (prev != null
                        && prev.direction() == c.direction()
                        && Duration.between(prev.ts(), c.ts()).compareTo(window) <= 0) {
                    set(
                            c,
                            Effect.IGNORED_DUPLICATE,
                            "Same direction as " + timeWithSeconds(prev.ts()) + ", within " + rules.duplicateMinutes()
                                    + " min");
                } else {
                    kept.add(c);
                }
            }
            return kept;
        }

        /** 3. An opposite pair within {@code turnedBackSeconds} (inclusive) cancels out; the rest is counted. */
        private List<Crossing> withoutTurnedBack(List<Crossing> kept) {
            Duration limit = Duration.ofSeconds(rules.turnedBackSeconds());
            List<Crossing> seq = new ArrayList<>();
            int i = 0;
            while (i < kept.size()) {
                Crossing a = kept.get(i);
                Crossing b = i + 1 < kept.size() ? kept.get(i + 1) : null;
                if (b != null
                        && a.direction() != b.direction()
                        && Duration.between(a.ts(), b.ts()).compareTo(limit) <= 0) {
                    long secs = Math.round(Duration.between(a.ts(), b.ts()).toMillis() / 1000.0);
                    set(
                            a,
                            Effect.IGNORED_TURNED_BACK,
                            a.direction() + " then " + b.direction() + " " + secs + " s later — turned back (limit "
                                    + rules.turnedBackSeconds() + " s)");
                    set(
                            b,
                            Effect.IGNORED_TURNED_BACK,
                            b.direction() + " " + secs + " s after " + a.direction() + " at " + timeWithSeconds(a.ts())
                                    + " — turned back");
                    i += 2;
                } else {
                    seq.add(a);
                    i++;
                }
            }
            return seq;
        }

        /** 4. One counted crossing: check-in, re-entry, stepping out or a (tentative) check-out. */
        private void count(Crossing c) {
            lastCounted = c;
            if (c.direction() == Direction.IN) {
                if (checkIn == null) {
                    checkIn = c;
                    String lateBy = c.ts().isAfter(shift.lateAfter(times))
                            ? " — late by " + TimeFormat.duration(Duration.between(times.start(), c.ts()))
                            : "";
                    set(c, Effect.CHECK_IN, "First IN of the shift day" + lateBy);
                } else if (checkOut != null) {
                    set(checkOut, Effect.MOVEMENT, "Was the tentative check-out until the IN at " + time(c.ts()));
                    set(c, Effect.MOVEMENT, "Came back in after " + time(checkOut.ts()) + " — check-out reopened");
                    checkOut = null;
                } else {
                    set(c, Effect.MOVEMENT, "Re-entry — check-in stays at " + time(checkIn.ts()));
                }
                inside = true;
                earlyCandidate = null;
                return;
            }
            String noIn = checkIn == null ? " (no IN seen this shift day)" : "";
            if (c.ts().isBefore(earlyLimit)) {
                set(
                        c,
                        Effect.MOVEMENT,
                        "Stepped out before shift end (" + time(times.end()) + ") — check-out stays open" + noIn);
                earlyCandidate = c;
            } else {
                if (checkOut != null) {
                    set(checkOut, Effect.MOVEMENT, "Replaced by a later OUT at " + time(c.ts()) + " — last OUT wins");
                }
                checkOut = c;
                earlyCandidate = null;
                set(c, Effect.CHECK_OUT, "OUT after shift end — last OUT wins" + noIn);
            }
            inside = false;
        }

        /** 6. SPAN: check-in to check-out (minus the break from 300 min on); INSIDE: the sum of IN→OUT intervals. */
        private Duration worked(List<Crossing> seq, boolean finalised) {
            Instant openUntil = min(ctx.now(), times.close());
            if (rules.workedHours() == WorkedHours.INSIDE) {
                Duration total = Duration.ZERO;
                Instant openIn = null;
                for (Crossing c : seq) {
                    if (c.direction() == Direction.IN) {
                        openIn = openIn == null ? c.ts() : openIn;
                    } else if (openIn != null) {
                        total = total.plus(Duration.between(openIn, c.ts()));
                        openIn = null;
                    }
                }
                return openIn != null && !finalised
                        ? total.plus(nonNegative(Duration.between(openIn, openUntil)))
                        : total;
            }
            Instant endTs = checkOut != null ? checkOut.ts() : inside ? openUntil : lastCounted.ts();
            Duration span = nonNegative(Duration.between(checkIn.ts(), endTs));
            return span.compareTo(BREAK_DEDUCTED_FROM) >= 0
                    ? nonNegative(span.minusMinutes(shift.breakMinutes()))
                    : span;
        }

        private void set(Crossing c, Effect effect, String reason) {
            effects.put(c.id(), new PunchEffect(effect, reason + suffix));
        }
    }

    private String time(Instant ts) {
        return format.time(LocalTime.ofInstant(ts, zone));
    }

    private String timeWithSeconds(Instant ts) {
        return format.timeWithSeconds(LocalTime.ofInstant(ts, zone));
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
