package com.krztechnologies.attend.organisation.domain;

import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Allocation;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Person;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Target;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plans a shift allocation (R9, the prototype's {@code planAllocation} / {@code endOrCancel}): which existing
 * allocations end or are cancelled, which are created, and who ends up on which shift on the first day. Pure: the same
 * plan is shown in the wizard's impact step and committed, so what the user confirmed is what is saved.
 */
public final class AllocationPlanner {

    /** What happens to members' individual allocations when a department gets a shift. */
    public enum IndividualMode {
        /** They stay and stay in effect (default). */
        KEEP,
        /** They end or are cancelled like the department's own overlapping allocations. */
        REPLACE
    }

    /** Why an existing allocation changes. */
    public enum Reason {
        SAME_TARGET,
        INDIVIDUAL_REPLACED
    }

    /**
     * The wizard's choices.
     *
     * @param employeeIds the targets when {@code targetType} is EMPLOYEE
     * @param departmentIds the targets when {@code targetType} is DEPARTMENT
     * @param until null = open-ended
     */
    public record Draft(
            Target targetType,
            List<UUID> employeeIds,
            List<Long> departmentIds,
            String shiftCode,
            LocalDate from,
            LocalDate until,
            IndividualMode individualMode) {

        public Draft {
            employeeIds = List.copyOf(new LinkedHashSet<>(employeeIds));
            departmentIds = List.copyOf(new LinkedHashSet<>(departmentIds));
        }
    }

    /** A person the plan may affect; only enabled people appear in the impact. */
    public record Member(Person person, boolean enabled) {}

    /**
     * An existing allocation the plan changes.
     *
     * @param cancelled true = cancelled (it starts on or after {@code from}); false = ends on {@code newUntil}
     */
    public record Conflict(Allocation allocation, boolean cancelled, LocalDate newUntil, Reason reason) {

        /** "ends on 31 Oct 2026" or "is cancelled". */
        public String changeText() {
            return cancelled ? "is cancelled" : "ends on " + OrgText.date(newUntil);
        }
    }

    /** One enabled person's shift on {@code from}, before and after the plan. */
    public record Impact(Person person, String shiftBefore, String shiftAfter, boolean overridden) {

        public boolean changes() {
            return !shiftBefore.equals(shiftAfter);
        }
    }

    /**
     * @param created the new allocations (code null until saved)
     * @param individualCount members of the target departments with an overlapping individual allocation
     */
    public record Plan(List<Conflict> conflicts, List<Allocation> created, List<Impact> impact, int individualCount) {}

    private AllocationPlanner() {}

    /**
     * @param existing every allocation (cancelled ones are ignored)
     * @param people everyone the targets may cover: the target employees, or all members of the target departments
     */
    public static Plan plan(
            Draft draft, Collection<Allocation> existing, Collection<Member> people, String defaultShiftCode) {
        Map<String, Allocation> working = new LinkedHashMap<>();
        existing.forEach(a -> working.put(a.code(), a));
        List<Conflict> conflicts = new ArrayList<>();

        for (Allocation a : List.copyOf(working.values())) {
            if (a.target() == draft.targetType() && isTarget(draft, a) && a.overlaps(draft.from(), draft.until())) {
                working.put(a.code(), endOrCancel(a, draft.from(), Reason.SAME_TARGET, conflicts));
            }
        }

        List<Member> covered =
                people.stream().filter(m -> covers(draft, m.person())).toList();
        Set<UUID> coveredIds = new LinkedHashSet<>();
        covered.forEach(m -> coveredIds.add(m.person().employeeId()));
        List<Allocation> individual = draft.targetType() == Target.DEPARTMENT
                ? working.values().stream()
                        .filter(a -> a.target() == Target.EMPLOYEE
                                && coveredIds.contains(a.employeeId())
                                && a.overlaps(draft.from(), draft.until()))
                        .toList()
                : List.of();
        if (draft.individualMode() == IndividualMode.REPLACE) {
            individual.forEach(
                    a -> working.put(a.code(), endOrCancel(a, draft.from(), Reason.INDIVIDUAL_REPLACED, conflicts)));
        }

        List<Allocation> created = new ArrayList<>();
        if (draft.targetType() == Target.EMPLOYEE) {
            draft.employeeIds().forEach(id -> created.add(newAllocation(draft, id, null)));
        } else {
            draft.departmentIds().forEach(id -> created.add(newAllocation(draft, null, id)));
        }
        List<Allocation> after = new ArrayList<>(working.values());
        after.addAll(created);

        List<Impact> impact = covered.stream()
                .filter(Member::enabled)
                .map(m -> {
                    Allocation before = ShiftCalendar.allocationFor(m.person(), draft.from(), existing)
                            .orElse(null);
                    Allocation now = ShiftCalendar.allocationFor(m.person(), draft.from(), after)
                            .orElse(null);
                    return new Impact(
                            m.person(),
                            before == null ? defaultShiftCode : before.shiftCode(),
                            now == null ? defaultShiftCode : now.shiftCode(),
                            draft.targetType() == Target.DEPARTMENT && now != null && now.target() == Target.EMPLOYEE);
                })
                .toList();
        return new Plan(List.copyOf(conflicts), List.copyOf(created), impact, individual.size());
    }

    private static boolean isTarget(Draft draft, Allocation a) {
        return draft.targetType() == Target.EMPLOYEE
                ? draft.employeeIds().contains(a.employeeId())
                : draft.departmentIds().contains(a.departmentId());
    }

    private static boolean covers(Draft draft, Person person) {
        return draft.targetType() == Target.EMPLOYEE
                ? draft.employeeIds().contains(person.employeeId())
                : person.departmentId() != null && draft.departmentIds().contains(person.departmentId());
    }

    /** Starts before {@code from}: ends the day before. Otherwise: cancelled. */
    private static Allocation endOrCancel(Allocation a, LocalDate from, Reason reason, List<Conflict> conflicts) {
        if (a.from().isBefore(from)) {
            LocalDate until = from.minusDays(1);
            conflicts.add(new Conflict(a, false, until, reason));
            return new Allocation(
                    a.code(), a.shiftCode(), a.target(), a.employeeId(), a.departmentId(), a.from(), until, false);
        }
        conflicts.add(new Conflict(a, true, null, reason));
        return new Allocation(
                a.code(), a.shiftCode(), a.target(), a.employeeId(), a.departmentId(), a.from(), a.until(), true);
    }

    private static Allocation newAllocation(Draft draft, UUID employeeId, Long departmentId) {
        return new Allocation(
                null,
                draft.shiftCode(),
                draft.targetType(),
                employeeId,
                departmentId,
                draft.from(),
                draft.until(),
                false);
    }
}
