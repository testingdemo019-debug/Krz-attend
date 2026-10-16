package com.krztechnologies.attend.rules.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The shift calendar (R1–R4), a pure function of the organisation's data: which shift an employee works on a day and
 * from which source, the shift times of a day, the shift day of a timestamp, holidays and weekly offs. Days are calendar
 * days in the organisation's time zone; instants are UTC.
 */
public final class ShiftCalendar {

    /** Where an employee's shift on a day comes from (precedence Employee > Department > Organisation default). */
    public enum Source {
        EMPLOYEE,
        DEPARTMENT,
        DEFAULT
    }

    /** Whom an allocation targets. */
    public enum Target {
        EMPLOYEE,
        DEPARTMENT
    }

    /** One shift with all its versions. */
    public record Shift(String code, List<ShiftTerms> versions) {
        public Shift {
            if (versions.isEmpty()) {
                throw new IllegalArgumentException("Shift " + code + " has no version");
            }
            versions = versions.stream()
                    .sorted(Comparator.comparing(ShiftTerms::versionFrom))
                    .toList();
        }

        /** The version with the greatest {@code versionFrom ≤ day} (the first version before that). */
        public ShiftTerms on(LocalDate day) {
            ShiftTerms chosen = versions.getFirst();
            for (ShiftTerms version : versions) {
                if (!version.versionFrom().isAfter(day)) {
                    chosen = version;
                }
            }
            return chosen;
        }
    }

    /**
     * A shift allocation (R9).
     *
     * @param until null = open-ended
     */
    public record Allocation(
            String code,
            String shiftCode,
            Target target,
            UUID employeeId,
            Long departmentId,
            LocalDate from,
            LocalDate until,
            boolean cancelled) {

        public boolean activeOn(LocalDate day) {
            return !cancelled && !from.isAfter(day) && (until == null || !until.isBefore(day));
        }

        /** Overlaps the period {@code [periodFrom, periodUntil]} (null until = open-ended), cancelled ones never. */
        public boolean overlaps(LocalDate periodFrom, LocalDate periodUntil) {
            return !cancelled
                    && (until == null || !until.isBefore(periodFrom))
                    && (periodUntil == null || !from.isAfter(periodUntil));
        }

        public boolean targets(Person person) {
            return target == Target.EMPLOYEE
                    ? employeeId.equals(person.employeeId())
                    : person.departmentId() != null && departmentId.equals(person.departmentId());
        }
    }

    /** What the calendar needs to know about an employee. */
    public record Person(UUID employeeId, Long departmentId, Long locationId) {}

    /**
     * A holiday (R4).
     *
     * @param code HOL-2026-01 (keys the attendance row's holiday)
     * @param locationId null = all locations
     * @param isPublic {@code PUBLIC}; {@code OPTIONAL} holidays are informational only
     */
    public record Holiday(String code, LocalDate date, String name, Long locationId, boolean isPublic) {}

    /** An employee's shift on a day and where it comes from. */
    public record Effective(ShiftTerms terms, Allocation allocation, Source source) {}

    /** The times of one shift day (R2). */
    public record Times(Instant start, Instant end, Instant windowStart, Instant close, Instant cutoff) {}

    private final Map<String, Shift> shifts;
    private final String defaultShiftCode;
    private final List<Allocation> allocations;
    private final List<Holiday> holidays;
    private final ZoneId zone;
    private final Duration absentCutoff;

    /**
     * @param absentCutoff attendance rules setting {@code absentCutoffHours} (R2 {@code cutoff})
     */
    public ShiftCalendar(
            Collection<Shift> shifts,
            String defaultShiftCode,
            Collection<Allocation> allocations,
            Collection<Holiday> holidays,
            ZoneId zone,
            Duration absentCutoff) {
        this.shifts = shifts.stream().collect(Collectors.toUnmodifiableMap(Shift::code, Function.identity()));
        if (!this.shifts.containsKey(defaultShiftCode)) {
            throw new IllegalArgumentException("Default shift " + defaultShiftCode + " is unknown");
        }
        this.defaultShiftCode = defaultShiftCode;
        this.allocations = List.copyOf(allocations);
        this.holidays = List.copyOf(holidays);
        this.zone = Objects.requireNonNull(zone);
        this.absentCutoff = Objects.requireNonNull(absentCutoff);
    }

    public ZoneId zone() {
        return zone;
    }

    public List<Allocation> allocations() {
        return allocations;
    }

    /** R1: the employee's shift version on {@code day} with its source. */
    public Effective effective(Person person, LocalDate day) {
        return effective(person, day, allocations);
    }

    /** R1 against another allocation list (a plan that is not saved yet). */
    public Effective effective(Person person, LocalDate day, Collection<Allocation> list) {
        Optional<Allocation> allocation = allocationFor(person, day, list);
        String shiftCode = allocation.map(Allocation::shiftCode).orElse(defaultShiftCode);
        Source source = allocation
                .map(a -> a.target() == Target.EMPLOYEE ? Source.EMPLOYEE : Source.DEPARTMENT)
                .orElse(Source.DEFAULT);
        return new Effective(shift(shiftCode).on(day), allocation.orElse(null), source);
    }

    /**
     * R1 steps 1–2: the active allocation that decides the day. An individual allocation beats a department one;
     * among the same kind the later {@code from} wins.
     */
    public static Optional<Allocation> allocationFor(Person person, LocalDate day, Collection<Allocation> list) {
        Allocation best = null;
        for (Allocation a : list) {
            if (!a.activeOn(day) || !a.targets(person)) {
                continue;
            }
            if (best == null
                    || (a.target() == Target.EMPLOYEE && best.target() == Target.DEPARTMENT)
                    || (a.target() == best.target() && a.from().isAfter(best.from()))) {
                best = a;
            }
        }
        return Optional.ofNullable(best);
    }

    public Shift shift(String code) {
        Shift shift = shifts.get(code);
        if (shift == null) {
            throw new IllegalArgumentException("Unknown shift " + code);
        }
        return shift;
    }

    public Shift defaultShift() {
        return shifts.get(defaultShiftCode);
    }

    /** R2: start, end (+1 day when it ends at or before it starts), window start, close and absent cutoff. */
    public Times times(ShiftTerms terms, LocalDate day) {
        return times(terms, day, zone, absentCutoff);
    }

    /** R2 with an explicit zone and cutoff (the rule tester runs unsaved attendance rules). */
    public static Times times(ShiftTerms terms, LocalDate day, ZoneId zone, Duration absentCutoff) {
        Instant start = day.atTime(terms.start()).atZone(zone).toInstant();
        Instant end = (terms.overnight() ? day.plusDays(1) : day)
                .atTime(terms.end())
                .atZone(zone)
                .toInstant();
        return new Times(
                start, end, start.minus(terms.windowBefore()), end.plus(terms.dayClose()), start.plus(absentCutoff));
    }

    /** R3: the shift day a timestamp belongs to for this employee (keeps a night shift on one day). */
    public LocalDate shiftDayOf(Person person, Instant ts) {
        LocalDate d = ts.atZone(zone).toLocalDate();
        for (LocalDate day : List.of(d, d.minusDays(1))) {
            Times t = times(effective(person, day).terms(), day);
            if (!ts.isBefore(t.windowStart()) && ts.isBefore(t.close())) {
                return day;
            }
        }
        LocalDate next = d.plusDays(1);
        return ts.isBefore(times(effective(person, next).terms(), next).windowStart()) ? d : next;
    }

    /** R4: the public holiday that applies to this employee on {@code day}, if any. */
    public Optional<Holiday> holiday(Person person, LocalDate day) {
        return holidays.stream()
                .filter(h -> h.isPublic() && h.date().equals(day))
                .filter(h -> h.locationId() == null || h.locationId().equals(person.locationId()))
                .findFirst();
    }

    /** The calendar's "today" for an instant. */
    public LocalDate dayOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }
}
