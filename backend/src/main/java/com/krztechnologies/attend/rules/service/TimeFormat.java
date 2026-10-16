package com.krztechnologies.attend.rules.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How the punch rules write times and durations into stored reasons, exactly like the prototype's {@code fmtTime}
 * and {@code fmtDuration}. Settings → General → time format picks the clock: {@code 12h} ("6:05 PM", the default)
 * or {@code 24h} ("18:05").
 */
public enum TimeFormat {
    H12,
    H24;

    /** The clock for the General setting {@code timeFormat} ({@code 12h} or {@code 24h}). */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** "1 Sep 2026", the prototype's {@code fmtDate} (audit entries, reasons, plan texts). */
    public static String date(LocalDate day) {
        return day.format(DATE);
    }

    public static TimeFormat of(String setting) {
        return switch (setting) {
            case "12h" -> H12;
            case "24h" -> H24;
            default -> throw new IllegalArgumentException("Time format must be 12h or 24h: " + setting);
        };
    }

    /** "9:20 AM" or "09:20". */
    public String time(LocalTime time) {
        return format(time, false);
    }

    /** "1 Sep 2026, 9:20 AM", the prototype's {@code fmtDateTime}, of a time already in the business zone. */
    public String dateTime(LocalDateTime at) {
        return date(at.toLocalDate()) + ", " + time(at.toLocalTime());
    }

    /** "9:20:40 AM" or "09:20:40". */
    public String timeWithSeconds(LocalTime time) {
        return format(time, true);
    }

    private String format(LocalTime time, boolean withSeconds) {
        String seconds = withSeconds ? ":" + twoDigits(time.getSecond()) : "";
        String minutes = ":" + twoDigits(time.getMinute()) + seconds;
        if (this == H24) {
            return twoDigits(time.getHour()) + minutes;
        }
        int hour = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        return hour + minutes + (time.getHour() < 12 ? " AM" : " PM");
    }

    /** "8h 05m", rounded to the nearest minute; negative durations show as "0h 00m". */
    public static String duration(Duration duration) {
        long minutes = Math.max(0, roundedMinutes(duration));
        return minutes / 60 + "h " + twoDigits((int) (minutes % 60)) + "m";
    }

    /** Whole minutes, rounded half up like the prototype's {@code Math.round}. */
    public static long roundedMinutes(Duration duration) {
        return Math.round(duration.toMillis() / 60_000.0);
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
