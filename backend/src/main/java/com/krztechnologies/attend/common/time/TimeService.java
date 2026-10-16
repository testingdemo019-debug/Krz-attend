package com.krztechnologies.attend.common.time;

import com.krztechnologies.attend.common.settings.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

/**
 * The only source of "now" and of the business time zone (Settings → General, default APP_TIMEZONE). Everything is
 * stored in UTC; days are calendar dates in this zone. Tests inject a fixed or mutable {@link Clock}.
 */
@Service
public class TimeService {

    private final Clock clock;
    private final SettingsService settings;

    public TimeService(Clock clock, SettingsService settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return settings.general().timezone();
    }

    public LocalDate today() {
        return dayOf(now());
    }

    public LocalDate dayOf(Instant instant) {
        return instant.atZone(zone()).toLocalDate();
    }

    public Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(zone()).toInstant();
    }

    public Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(zone()).toInstant();
    }

    public ZonedDateTime zoned(Instant instant) {
        return instant.atZone(zone());
    }
}
