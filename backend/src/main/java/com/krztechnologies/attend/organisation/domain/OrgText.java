package com.krztechnologies.attend.organisation.domain;

import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * The prototype's wording for organisation texts that the server writes (audit entries, plan conflicts, shift sources):
 * dates as "1 Sep 2026" ({@code fmtDate}), counts as "2 employees" ({@code plural}).
 */
public final class OrgText {

    private static final List<String> WEEKDAYS = List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat");

    private OrgText() {}

    /** "1 Sep 2026". */
    public static String date(LocalDate day) {
        return TimeFormat.date(day);
    }

    /** "1 employee", "2 employees". */
    public static String plural(long count, String noun) {
        return plural(count, noun, noun + "s");
    }

    /** "1 person", "3 people". */
    public static String plural(long count, String singular, String pluralForm) {
        return count + " " + (count == 1 ? singular : pluralForm);
    }

    /** "Sun" … "Sat" for weekday numbers 0 … 6. */
    public static String weekdayLabel(int number) {
        return WEEKDAYS.get(number);
    }
}
