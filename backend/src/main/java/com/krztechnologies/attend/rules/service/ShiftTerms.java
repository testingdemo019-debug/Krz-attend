package com.krztechnologies.attend.rules.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * The rules of one shift version (R2): times, break, weekly offs, graces and thresholds. Durations are kept exact
 * (minutes), the database stores hours with two decimals.
 *
 * @param versionFrom the first day this version applies to
 * @param weeklyOffs days without work
 */
public record ShiftTerms(
        String shiftCode,
        String shiftName,
        String colour,
        LocalDate versionFrom,
        LocalTime start,
        LocalTime end,
        int breakMinutes,
        Set<DayOfWeek> weeklyOffs,
        int lateGraceMinutes,
        int earlyGraceMinutes,
        Duration halfDayBelow,
        Duration absentBelow,
        Duration windowBefore,
        Duration dayClose) {

    public ShiftTerms {
        weeklyOffs = Set.copyOf(weeklyOffs);
    }

    /** The shift ends on the next calendar day ("(+1 day)"). */
    public boolean overnight() {
        return !end.isAfter(start);
    }

    public Duration duration() {
        Duration span = Duration.between(start, end);
        return overnight() ? span.plusDays(1) : span;
    }

    /** "09:30–18:30" or "21:00–06:00 (+1 day)", as the prototype shows timings. */
    public String timingText() {
        return timingText(start, end);
    }

    /** The one timing text: "09:30–18:30", or "21:00–06:00 (+1 day)" when it ends at or before it starts. */
    public static String timingText(LocalTime start, LocalTime end) {
        return start + "–" + end + (end.isAfter(start) ? "" : " (+1 day)");
    }

    /** A check-in after this instant is late (shift start + late grace). */
    public Instant lateAfter(ShiftCalendar.Times times) {
        return times.start().plus(Duration.ofMinutes(lateGraceMinutes));
    }

    /** A final check-out before this instant is an early exit (shift end − early-exit grace). */
    public Instant earlyLimit(ShiftCalendar.Times times) {
        return times.end().minus(Duration.ofMinutes(earlyGraceMinutes));
    }

    public boolean isWeeklyOff(LocalDate day) {
        return weeklyOffs.contains(day.getDayOfWeek());
    }

    /**
     * Weekly offs as stored: 0 = Sunday … 6 = Saturday (the prototype's weekday numbers).
     *
     * @param number 0–6
     */
    public static DayOfWeek weekday(int number) {
        if (number < 0 || number > 6) {
            throw new IllegalArgumentException("Weekday number must be 0–6: " + number);
        }
        return number == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(number);
    }

    public static int weekdayNumber(DayOfWeek day) {
        return day == DayOfWeek.SUNDAY ? 0 : day.getValue();
    }
}
