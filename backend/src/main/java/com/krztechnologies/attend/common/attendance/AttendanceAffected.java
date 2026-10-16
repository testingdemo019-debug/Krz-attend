package com.krztechnologies.attend.common.attendance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Something that changes how employee-days are evaluated changed: a shift version, the default shift, an allocation, a
 * holiday, a department move, employment details or tracking (R7 triggers). Published inside the change's transaction;
 * the attendance module recomputes the days of the scope from {@code from} to {@code to} before the commit (only days
 * up to today; at most the last 31 days). The windows follow the prototype.
 */
public record AttendanceAffected(Scope scope, LocalDate from, LocalDate to) {

    /** Whose days change. */
    public sealed interface Scope permits Employees, Department, Shift, Location {}

    public record Employees(Set<UUID> ids) implements Scope {}

    /** The members of a department. */
    public record Department(long id) implements Scope {}

    /** Everyone whose effective shift on one of the days is this one. */
    public record Shift(String code) implements Scope {}

    /** @param id the location of a holiday; null = every location */
    public record Location(Long id) implements Scope {}

    public static AttendanceAffected employees(Collection<UUID> ids, LocalDate from, LocalDate to) {
        return new AttendanceAffected(new Employees(Set.copyOf(ids)), from, to);
    }

    public static AttendanceAffected department(long id, LocalDate from, LocalDate to) {
        return new AttendanceAffected(new Department(id), from, to);
    }

    public static AttendanceAffected shift(String code, LocalDate from, LocalDate to) {
        return new AttendanceAffected(new Shift(code), from, to);
    }

    public static AttendanceAffected location(Long id, LocalDate from, LocalDate to) {
        return new AttendanceAffected(new Location(id), from, to);
    }
}
