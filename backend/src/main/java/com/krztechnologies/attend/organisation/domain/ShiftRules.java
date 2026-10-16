package com.krztechnologies.attend.organisation.domain;

import com.krztechnologies.attend.common.error.ErrorResponse.FieldError;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The timings and rules of one shift version as the shift wizard edits them (R2), with the wizard's validation and its
 * messages. The database checks are the last line of defence behind {@link #problems()}.
 *
 * @param weeklyOffs weekday numbers 0 (Sunday) … 6 (Saturday)
 */
public record ShiftRules(
        LocalTime start,
        LocalTime end,
        int breakMinutes,
        List<Integer> weeklyOffs,
        int lateGraceMinutes,
        int earlyGraceMinutes,
        BigDecimal halfDayBelowHours,
        BigDecimal absentBelowHours,
        BigDecimal windowBeforeHours,
        BigDecimal dayCloseHours) {

    private static final int MIN_SHIFT_MINUTES = 4 * 60;
    private static final int MAX_SHIFT_MINUTES = 14 * 60;
    /** Window, shift and day close of one shift day must leave an hour before the next day's window opens. */
    private static final int MAX_DAY_MINUTES = 23 * 60;

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    public ShiftRules {
        weeklyOffs = weeklyOffs.stream().distinct().sorted().toList();
    }

    /** Length of the shift: end − start, plus a day when it ends at or before it starts ("(+1 day)"). */
    public int durationMinutes() {
        int minutes = (end.toSecondOfDay() - start.toSecondOfDay()) / 60;
        return minutes <= 0 ? minutes + 1440 : minutes;
    }

    /** "09:30–18:30" or "21:00–06:00 (+1 day)". */
    public String timingText() {
        return ShiftTerms.timingText(start, end);
    }

    /** Every rule the wizard checks (R2), as field errors with the prototype's messages; empty when valid. */
    public List<FieldError> problems() {
        List<FieldError> problems = new ArrayList<>();
        if (start.getSecond() != 0 || start.getNano() != 0) {
            problems.add(new FieldError("start", "Use hours and minutes."));
        }
        if (end.getSecond() != 0 || end.getNano() != 0) {
            problems.add(new FieldError("end", "Use hours and minutes."));
        }
        int duration = durationMinutes();
        if (duration < MIN_SHIFT_MINUTES) {
            problems.add(new FieldError("end", "A shift must be at least 4 hours long."));
        } else if (duration > MAX_SHIFT_MINUTES) {
            problems.add(new FieldError("end", "A shift can’t be longer than 14 hours."));
        }
        if (weeklyOffs.stream().anyMatch(d -> d < 0 || d > 6)) {
            problems.add(new FieldError("weeklyOffs", "Use weekday numbers 0 (Sunday) to 6 (Saturday)."));
        } else if (weeklyOffs.size() > 3) {
            problems.add(new FieldError("weeklyOffs", "Choose at most 3 weekly offs."));
        }
        if (breakMinutes < 0 || breakMinutes > 120) {
            problems.add(new FieldError("breakMinutes", "Between 0 and 120 minutes."));
        }
        if (lateGraceMinutes < 0 || lateGraceMinutes > 120) {
            problems.add(new FieldError("lateGraceMinutes", "Between 0 and 120 minutes."));
        }
        if (earlyGraceMinutes < 0 || earlyGraceMinutes > 120) {
            problems.add(new FieldError("earlyGraceMinutes", "Between 0 and 120 minutes."));
        }
        hoursProblem("halfDayBelowHours", halfDayBelowHours, BigDecimal.ZERO, TWELVE, problems);
        hoursProblem("absentBelowHours", absentBelowHours, BigDecimal.ZERO, TWELVE, problems);
        hoursProblem("windowBeforeHours", windowBeforeHours, BigDecimal.ONE, BigDecimal.valueOf(6), problems);
        hoursProblem("dayCloseHours", dayCloseHours, BigDecimal.ONE, BigDecimal.valueOf(8), problems);
        if (absentBelowHours.compareTo(halfDayBelowHours) >= 0) {
            problems.add(new FieldError("absentBelowHours", "Must be lower than the half-day limit."));
        }
        BigDecimal shiftMinutes = BigDecimal.valueOf(duration);
        if (minutes(halfDayBelowHours).compareTo(shiftMinutes) >= 0) {
            problems.add(new FieldError("halfDayBelowHours", "Must be shorter than the shift."));
        } else if (halfDayBelowHours.signum() <= 0) {
            problems.add(new FieldError("halfDayBelowHours", "Must be more than 0 hours."));
        }
        BigDecimal dayMinutes = minutes(windowBeforeHours).add(shiftMinutes).add(minutes(dayCloseHours));
        if (dayMinutes.compareTo(BigDecimal.valueOf(MAX_DAY_MINUTES)) > 0) {
            problems.add(new FieldError(
                    "dayCloseHours", "Window, shift and day close must fit in 23 hours so shift days don’t overlap."));
        }
        return problems;
    }

    /** The rules as the calendar uses them (R1–R4). */
    public ShiftTerms toTerms(String shiftCode, String shiftName, String colour, LocalDate versionFrom) {
        Set<DayOfWeek> offs =
                weeklyOffs.stream().map(ShiftTerms::weekday).collect(Collectors.toCollection(HashSet::new));
        return new ShiftTerms(
                shiftCode,
                shiftName,
                colour,
                versionFrom,
                start,
                end,
                breakMinutes,
                offs,
                lateGraceMinutes,
                earlyGraceMinutes,
                hours(halfDayBelowHours),
                hours(absentBelowHours),
                hours(windowBeforeHours),
                hours(dayCloseHours));
    }

    /** "Sat, Sun" in the prototype's Monday-first order, or "None". */
    public String weeklyOffText() {
        if (weeklyOffs.isEmpty()) {
            return "None";
        }
        List<String> labels = new ArrayList<>();
        for (int day : List.of(1, 2, 3, 4, 5, 6, 0)) {
            if (weeklyOffs.contains(day)) {
                labels.add(OrgText.weekdayLabel(day));
            }
        }
        return String.join(", ", labels);
    }

    private static void hoursProblem(
            String field, BigDecimal value, BigDecimal min, BigDecimal max, List<FieldError> problems) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            problems.add(new FieldError(
                    field, "Between " + min.toPlainString() + " and " + max.toPlainString() + " hours."));
        } else if (value.stripTrailingZeros().scale() > 2) {
            problems.add(new FieldError(field, "Use at most two decimals."));
        }
    }

    private static BigDecimal minutes(BigDecimal hours) {
        return hours.multiply(SIXTY);
    }

    /** Hours with two decimals are whole seconds, so the conversion is exact. */
    private static Duration hours(BigDecimal value) {
        return Duration.ofSeconds(value.multiply(BigDecimal.valueOf(3600)).longValue());
    }
}
