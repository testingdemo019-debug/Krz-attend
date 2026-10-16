package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.AllocationPlanner;
import com.krztechnologies.attend.organisation.domain.AllocationPlanner.Conflict;
import com.krztechnologies.attend.organisation.domain.AllocationPlanner.Draft;
import com.krztechnologies.attend.organisation.domain.AllocationPlanner.IndividualMode;
import com.krztechnologies.attend.organisation.domain.AllocationPlanner.Plan;
import com.krztechnologies.attend.organisation.domain.Department;
import com.krztechnologies.attend.organisation.domain.OrgText;
import com.krztechnologies.attend.organisation.domain.Shift;
import com.krztechnologies.attend.organisation.domain.ShiftAllocation;
import com.krztechnologies.attend.organisation.repository.DepartmentRepository;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries.AllocationChange;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries.PersonRow;
import com.krztechnologies.attend.organisation.repository.ShiftAllocationRepository;
import com.krztechnologies.attend.organisation.repository.ShiftRepository;
import com.krztechnologies.attend.organisation.service.OrganisationViews.AllocationRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ChangeView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.CommitResult;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ConflictView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.HistoryEntry;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ImpactRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.LookupRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.PlanView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ShiftRef;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Target;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift allocation (R9): plan (the wizard's impact step) and commit with the same planner, so exactly the confirmed
 * plan is saved; end and cancel; the list, the history per target and "who works which shift on a date". The database
 * exclusion constraint is the last line of defence: its violation becomes 409 {@code ALLOCATION_OVERLAP}.
 */
@Service
public class AllocationService {

    private static final String OVERLAP_CONSTRAINT = "ex_shift_allocation_overlap";

    private final ShiftAllocationRepository allocations;
    private final ShiftRepository shifts;
    private final DepartmentRepository departments;
    private final OrganisationQueries queries;
    private final OrganisationDirectory directory;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final IdGenerator ids;
    private final ApplicationEventPublisher events;

    public AllocationService(
            ShiftAllocationRepository allocations,
            ShiftRepository shifts,
            DepartmentRepository departments,
            OrganisationQueries queries,
            OrganisationDirectory directory,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            IdGenerator ids,
            ApplicationEventPublisher events) {
        this.allocations = allocations;
        this.shifts = shifts;
        this.departments = departments;
        this.queries = queries;
        this.directory = directory;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.ids = ids;
        this.events = events;
    }

    /**
     * The wizard's choices as the API receives them.
     *
     * @param targetIds department codes, or employee ids
     * @param until null = open-ended
     */
    public record Request(
            Target targetType,
            List<String> targetIds,
            String shiftCode,
            LocalDate from,
            LocalDate until,
            IndividualMode individualMode,
            String note) {}

    /**
     * List filters (all optional).
     *
     * @param target department code or employee id
     * @param activeOn only allocations in effect on that day
     */
    /**
     * @param targetTypes empty = both
     * @param shifts shift codes; empty = every shift
     */
    public record Filter(
            Set<Target> targetTypes,
            String target,
            Set<String> shifts,
            LocalDate activeOn,
            Set<ShiftAllocation.State> states,
            String q) {}

    @Transactional(readOnly = true)
    public PlanView plan(Request request) {
        Context context = context();
        Draft draft = resolve(request, context);
        Plan plan = plan(draft, context);
        List<ImpactRow> impact = plan.impact().stream()
                .map(i -> new ImpactRow(
                        i.person().employeeId(),
                        context.person(i.person().employeeId()).displayName(),
                        context.shiftRef(i.shiftBefore()),
                        context.shiftRef(i.shiftAfter()),
                        i.overridden(),
                        i.changes()))
                .sorted(Comparator.comparing((ImpactRow r) -> !r.changes())
                        .thenComparing(ImpactRow::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<ConflictView> conflicts = plan.conflicts().stream()
                .map(c -> new ConflictView(
                        c.allocation().code(),
                        context.targetName(
                                c.allocation().target(),
                                c.allocation().employeeId(),
                                c.allocation().departmentId()),
                        context.shiftRef(c.allocation().shiftCode()),
                        c.allocation().from(),
                        c.allocation().until(),
                        c.changeText(),
                        c.reason().name()))
                .toList();
        long changing = impact.stream().filter(ImpactRow::changes).count();
        long overridden = impact.stream().filter(ImpactRow::overridden).count();
        long unchanged =
                impact.stream().filter(r -> !r.changes() && !r.overridden()).count();
        return new PlanView(conflicts, impact, plan.individualCount(), changing, unchanged, overridden);
    }

    /** Re-plans inside the transaction and saves exactly that plan; every change is audited. */
    @Transactional
    public CommitResult commit(Request request) {
        Context context = context();
        return save(resolve(request, context), Form.clean(request.note()), context);
    }

    /** Employees → bulk "Allocate shift": individual allocations for these people, through the same planner. */
    @Transactional
    public CommitResult commitForEmployees(
            List<UUID> employeeIds, String shiftCode, LocalDate from, LocalDate until, String note) {
        return commit(new Request(
                Target.EMPLOYEE,
                employeeIds.stream().map(UUID::toString).toList(),
                shiftCode,
                from,
                until,
                IndividualMode.KEEP,
                note));
    }

    /** A department's default shift from today (department editor), through the same planner. */
    @Transactional
    CommitResult allocateToNewDepartment(Department department, Shift shift, String note) {
        Context context = context();
        Draft draft = new Draft(
                Target.DEPARTMENT,
                List.of(),
                List.of(department.getId()),
                shift.getCode(),
                time.today(),
                null,
                IndividualMode.KEEP);
        return save(draft, note, context);
    }

    /** Deactivating a department: its active allocations end today, scheduled ones are cancelled. */
    @Transactional
    void endForDepartment(Department department) {
        LocalDate today = time.today();
        String target = department.getName();
        for (ShiftAllocation a : allocations.findByDepartmentId(department.getId())) {
            ShiftAllocation.State state = a.state(today);
            if (state == ShiftAllocation.State.SCHEDULED) {
                a.cancel(currentUser.employeeId(), time.now());
                audit.record(
                        AuditCategory.ORGANISATION,
                        "Cancelled shift allocation",
                        a.getCode() + " · " + target,
                        null,
                        "Department deactivated",
                        null);
            } else if (state == ShiftAllocation.State.ACTIVE && !today.equals(a.getValidUntil())) {
                String before = untilText(a.getValidUntil());
                a.endOn(today);
                audit.record(
                        AuditCategory.ORGANISATION,
                        "Ended shift allocation",
                        a.getCode() + " · " + target,
                        before,
                        "Department deactivated",
                        null);
            }
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AllocationRow> list(Filter filter, Integer page, Integer size, String sort) {
        return Pages.of(
                all(filter),
                page,
                size,
                sort,
                Map.of(
                        "from", Comparator.comparing(AllocationRow::from),
                        "until",
                                Comparator.comparing(
                                        AllocationRow::until, Comparator.nullsLast(Comparator.naturalOrder())),
                        "target", Pages.text(AllocationRow::targetName),
                        "shift", Pages.text(r -> r.shift().name()),
                        "state", Pages.text(AllocationRow::state),
                        "created",
                                Comparator.comparing(
                                        AllocationRow::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))),
                Sort.by(Sort.Direction.DESC, "from"));
    }

    /** Every matching allocation (list and export). */
    @Transactional(readOnly = true)
    public List<AllocationRow> all(Filter filter) {
        Context context = context();
        String needle = filter.q() == null ? "" : filter.q().trim().toLowerCase(Locale.ROOT);
        return allocations.findAll().stream()
                .filter(a -> filter.targetTypes() == null
                        || filter.targetTypes().isEmpty()
                        || filter.targetTypes().contains(a.getTargetType()))
                .filter(a -> filter.target() == null || filter.target().equals(context.targetId(a)))
                .filter(a -> filter.shifts() == null
                        || filter.shifts().isEmpty()
                        || filter.shifts()
                                .contains(context.shifts().get(a.getShiftId()).getCode()))
                .filter(a -> filter.activeOn() == null || a.toCalendar(null).activeOn(filter.activeOn()))
                .filter(a -> filter.states() == null
                        || filter.states().isEmpty()
                        || filter.states().contains(a.state(context.today())))
                .map(context::row)
                .filter(r -> needle.isEmpty()
                        || (r.code() + " " + r.targetName() + " " + r.shift().name() + " "
                                        + (r.note() == null ? "" : r.note()))
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .toList();
    }

    /** Every allocation by state, by what it applies to and by shift (the list's filter counts). */
    @Transactional(readOnly = true)
    public OrganisationViews.AllocationFacets facets() {
        List<AllocationRow> rows = all(new Filter(null, null, null, null, null, null));
        Map<String, String> states = new LinkedHashMap<>();
        states.put("ACTIVE", "Active");
        states.put("SCHEDULED", "Scheduled");
        states.put("ENDED", "Ended");
        states.put("CANCELLED", "Cancelled");
        Map<String, String> types = new LinkedHashMap<>();
        types.put("DEPARTMENT", "Department");
        types.put("EMPLOYEE", "Employee");
        Map<String, String> shifts = new LinkedHashMap<>();
        context().shifts().values().stream()
                .sorted(Comparator.comparing(s -> s.getName().toLowerCase(Locale.ROOT)))
                .forEach(s -> shifts.put(s.getCode(), s.getName()));
        return new OrganisationViews.AllocationFacets(
                Pages.count(rows, AllocationRow::state, states),
                Pages.count(rows, AllocationRow::targetType, types),
                Pages.count(rows, r -> r.shift().code(), shifts));
    }

    /** Allocations of one shift for its drawer. */
    @Transactional(readOnly = true)
    public List<AllocationRow> ofShift(String shiftCode) {
        return all(new Filter(null, null, Set.of(shiftCode), null, null, null));
    }

    @Transactional
    public AllocationRow end(String code, LocalDate until) {
        ShiftAllocation allocation = find(code);
        LocalDate today = time.today();
        ShiftAllocation.State state = allocation.state(today);
        if (state == ShiftAllocation.State.CANCELLED || state == ShiftAllocation.State.ENDED) {
            throw new Errors.Conflict(
                    "ALLOCATION_NOT_ACTIVE",
                    state == ShiftAllocation.State.CANCELLED
                            ? "This allocation is cancelled."
                            : "This allocation has already ended.");
        }
        LocalDate earliest = allocation.getValidFrom().isAfter(today) ? allocation.getValidFrom() : today;
        Form form = new Form();
        if (form.check(until == null, "until", "Choose the last day on this shift.")) {
            form.check(until.isBefore(earliest), "until", "Choose " + OrgText.date(earliest) + " or later.");
        }
        form.throwIfInvalid();
        Context context = context();
        String before = untilText(allocation.getValidUntil());
        allocation.endOn(until);
        flushChecked();
        audit.record(
                AuditCategory.ORGANISATION,
                "Ended shift allocation",
                allocation.getCode() + " · " + context.row(allocation).targetName(),
                before,
                "until " + OrgText.date(until),
                null);
        return context.row(allocation);
    }

    /** Only an allocation that has not started can be cancelled; one that has started is ended instead. */
    @Transactional
    public AllocationRow cancel(String code) {
        ShiftAllocation allocation = find(code);
        if (allocation.isCancelled()) {
            throw new Errors.Conflict("ALLOCATION_NOT_ACTIVE", "This allocation is already cancelled.");
        }
        if (!allocation.getValidFrom().isAfter(time.today())) {
            throw new Errors.Conflict("ALLOCATION_STARTED", "It has already started — end it instead.");
        }
        Context context = context();
        allocation.cancel(currentUser.employeeId(), time.now());
        AllocationRow row = context.row(allocation);
        audit.record(
                AuditCategory.ORGANISATION,
                "Cancelled shift allocation",
                allocation.getCode() + " · " + row.targetName(),
                row.shift().name() + " from " + OrgText.date(allocation.getValidFrom()),
                "Cancelled",
                null);
        return row;
    }

    /** Every allocation of one target, newest first, each with its recorded changes. */
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(Target targetType, String targetId) {
        Context context = context();
        List<ShiftAllocation> mine = allocations.findAll().stream()
                .filter(a -> a.getTargetType() == targetType && targetId.equals(context.targetId(a)))
                .sorted(Comparator.comparing(ShiftAllocation::getValidFrom)
                        .thenComparing(ShiftAllocation::getCode)
                        .reversed())
                .toList();
        Map<String, List<AllocationChange>> changes =
                queries
                        .allocationChanges(
                                mine.stream().map(ShiftAllocation::getCode).toList())
                        .stream()
                        .collect(Collectors.groupingBy(AllocationChange::allocationCode));
        return mine.stream()
                .map(a -> new HistoryEntry(
                        context.row(a),
                        changes.getOrDefault(a.getCode(), List.of()).stream()
                                .map(c -> new ChangeView(c.at(), c.actor(), c.action(), c.before(), c.after()))
                                .toList()))
                .toList();
    }

    /** "Who works which shift on a date": enabled employees matching {@code q} (name or Employee ID). */
    @Transactional(readOnly = true)
    public PageResponse<LookupRow> lookup(LocalDate day, String q, Integer page, Integer size) {
        Context context = context();
        LocalDate date = day == null ? context.today() : day;
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> departmentNames = context.departmentNames();
        List<LookupRow> rows = context.people().stream()
                .filter(PersonRow::enabled)
                .filter(p -> needle.isEmpty()
                        || (p.displayName() + " " + (p.employeeNumber() == null ? "" : p.employeeNumber()))
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .map(p -> {
                    ShiftCalendar.Effective effective = context.calendar().effective(p.person(), date);
                    ShiftTerms terms = effective.terms();
                    Optional<ShiftCalendar.Holiday> holiday = context.calendar().holiday(p.person(), date);
                    String dayType =
                            holiday.isPresent() ? "HOLIDAY" : terms.isWeeklyOff(date) ? "WEEKLY_OFF" : "WORKING_DAY";
                    return new LookupRow(
                            p.id(),
                            p.displayName(),
                            p.employeeNumber(),
                            p.departmentId() == null ? null : departmentNames.get(p.departmentId()),
                            context.shiftRef(terms.shiftCode()),
                            terms.timingText(),
                            effective.source().name(),
                            OrganisationDirectory.sourceText(
                                    effective, id -> Optional.ofNullable(departmentNames.get(id))),
                            terms.versionFrom(),
                            dayType,
                            holiday.map(ShiftCalendar.Holiday::name).orElse(null));
                })
                .toList();
        return Pages.of(rows, page, size, null, Map.of("name", Pages.text(LookupRow::displayName)), Sort.by("name"));
    }

    private CommitResult save(Draft draft, String note, Context context) {
        Plan plan = plan(draft, context);
        AttendPrincipal me = currentUser.require();
        for (Conflict conflict : plan.conflicts()) {
            ShiftAllocation existing = find(conflict.allocation().code());
            if (conflict.cancelled()) {
                existing.cancel(me.employeeId(), time.now());
            } else {
                existing.endOn(conflict.newUntil());
            }
            audit.record(
                    AuditCategory.ORGANISATION,
                    conflict.cancelled() ? "Cancelled shift allocation" : "Ended shift allocation",
                    existing.getCode() + " · " + context.row(existing).targetName(),
                    null,
                    (conflict.reason() == AllocationPlanner.Reason.SAME_TARGET
                                    ? "Replaced by a new allocation"
                                    : "Individual allocation replaced by the department shift")
                            + " (" + conflict.changeText() + ")",
                    null);
        }
        flushChecked();
        Shift shift = context.shiftByCode(draft.shiftCode());
        List<String> created = new ArrayList<>();
        for (ShiftCalendar.Allocation a : plan.created()) {
            ShiftAllocation allocation = new ShiftAllocation(
                    ids.next(CodeType.ALLOCATION),
                    shift.getId(),
                    a.target(),
                    a.employeeId(),
                    a.departmentId(),
                    a.from(),
                    a.until(),
                    note,
                    me.employeeId(),
                    me.displayName());
            try {
                allocations.saveAndFlush(allocation);
            } catch (DataIntegrityViolationException e) {
                throw overlapOr(e);
            }
            created.add(allocation.getCode());
            audit.record(
                    AuditCategory.ORGANISATION,
                    "Allocated shift",
                    allocation.getCode() + " · " + context.targetName(a.target(), a.employeeId(), a.departmentId()),
                    null,
                    shift.getName() + " from " + OrgText.date(a.from())
                            + (a.until() == null ? "" : " until " + OrgText.date(a.until())),
                    null);
        }
        long changing =
                plan.impact().stream().filter(AllocationPlanner.Impact::changes).count();
        LocalDate today = time.today();
        if (!draft.from().isAfter(today) && changing > 0) {
            events.publishEvent(AttendanceAffected.employees(
                    plan.impact().stream()
                            .filter(AllocationPlanner.Impact::changes)
                            .map(i -> i.person().employeeId())
                            .toList(),
                    today,
                    today));
        }
        return new CommitResult(created, plan.conflicts().size(), changing);
    }

    private static Plan plan(Draft draft, Context context) {
        return AllocationPlanner.plan(
                draft,
                context.calendar().allocations(),
                context.people().stream()
                        .map(p -> new AllocationPlanner.Member(p.person(), p.enabled()))
                        .toList(),
                context.calendar().defaultShift().code());
    }

    /** Checks the wizard's choices (the prototype's step messages) and turns codes and ids into a planner draft. */
    private Draft resolve(Request request, Context context) {
        Form form = new Form();
        Shift shift = null;
        if (form.check(request.shiftCode() == null, "shiftCode", "Choose a shift.")) {
            shift = shifts.findByCode(request.shiftCode())
                    .filter(Shift::isActive)
                    .orElse(null);
            form.check(shift == null, "shiftCode", "Choose an active shift.");
        }
        Target type = request.targetType() == null ? Target.DEPARTMENT : request.targetType();
        List<String> targetIds = request.targetIds() == null ? List.of() : request.targetIds();
        List<UUID> employeeIds = new ArrayList<>();
        List<Long> departmentIds = new ArrayList<>();
        if (form.check(targetIds.isEmpty(), "targetIds", "Choose at least one.")) {
            for (String id : targetIds) {
                if (type == Target.DEPARTMENT) {
                    Optional<Department> department = departments.findByCode(id).filter(Department::isActive);
                    department.ifPresent(d -> departmentIds.add(d.getId()));
                    form.check(department.isEmpty(), "targetIds", "Choose active departments only.");
                } else {
                    Optional<PersonRow> person =
                            parseUuid(id).flatMap(context::findPerson).filter(PersonRow::enabled);
                    person.ifPresent(p -> employeeIds.add(p.id()));
                    form.check(person.isEmpty(), "targetIds", "Choose enabled employees only.");
                }
            }
        }
        LocalDate today = context.today();
        if (form.check(request.from() == null, "from", "Choose a date.")) {
            form.check(request.from().isBefore(today), "from", "Choose today or a later date.");
            form.check(
                    request.until() != null && request.until().isBefore(request.from()),
                    "until",
                    "Must be on or after the start date.");
        }
        form.check(request.note() != null && request.note().length() > 500, "note", "Use at most 500 characters.");
        form.throwIfInvalid();
        return new Draft(
                type,
                employeeIds,
                departmentIds,
                shift.getCode(),
                request.from(),
                request.until(),
                request.individualMode() == null ? IndividualMode.KEEP : request.individualMode());
    }

    private ShiftAllocation find(String code) {
        return allocations.findByCode(code).orElseThrow(() -> new Errors.NotFound("Allocation not found."));
    }

    private void flushChecked() {
        try {
            allocations.flush();
        } catch (DataIntegrityViolationException e) {
            throw overlapOr(e);
        }
    }

    private static RuntimeException overlapOr(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        if (message != null && message.contains(OVERLAP_CONSTRAINT)) {
            return new Errors.Conflict(
                    "ALLOCATION_OVERLAP",
                    "Another allocation of the same employee or department covers these dates. Reload and try again.");
        }
        return e;
    }

    private static String untilText(LocalDate until) {
        return until == null ? "open-ended" : OrgText.date(until);
    }

    private static Optional<UUID> parseUuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private Context context() {
        List<PersonRow> people = queries.people();
        return new Context(
                time.today(),
                directory.calendar(),
                directory.shiftsById(),
                departments.findAll().stream().collect(Collectors.toMap(Department::getId, Function.identity())),
                people,
                people.stream().collect(Collectors.toMap(PersonRow::id, Function.identity())));
    }

    /** Everything one request reads once: today, the calendar, shifts, departments and people. */
    private record Context(
            LocalDate today,
            ShiftCalendar calendar,
            Map<Long, Shift> shifts,
            Map<Long, Department> departments,
            List<PersonRow> people,
            Map<UUID, PersonRow> peopleById) {

        Optional<PersonRow> findPerson(UUID id) {
            return Optional.ofNullable(peopleById.get(id));
        }

        PersonRow person(UUID id) {
            return peopleById.get(id);
        }

        Map<Long, String> departmentNames() {
            return departments.values().stream().collect(Collectors.toMap(Department::getId, Department::getName));
        }

        Shift shiftByCode(String code) {
            return shifts.values().stream()
                    .filter(s -> s.getCode().equals(code))
                    .findFirst()
                    .orElseThrow();
        }

        ShiftRef shiftRef(String code) {
            Shift shift = shiftByCode(code);
            return new ShiftRef(shift.getCode(), shift.getName(), shift.getColour());
        }

        String targetName(Target type, UUID employeeId, Long departmentId) {
            if (type == Target.DEPARTMENT) {
                Department department = departments.get(departmentId);
                return department == null ? "—" : department.getName();
            }
            PersonRow person = peopleById.get(employeeId);
            return person == null ? "—" : person.displayName();
        }

        /** Department code or employee id, as the API names targets. */
        String targetId(ShiftAllocation a) {
            return a.getTargetType() == Target.DEPARTMENT
                    ? departments.get(a.getDepartmentId()).getCode()
                    : a.getEmployeeId().toString();
        }

        AllocationRow row(ShiftAllocation a) {
            Shift shift = shifts.get(a.getShiftId());
            LocalDate shown = a.getValidFrom().isAfter(today) ? a.getValidFrom() : today;
            String detail;
            if (a.getTargetType() == Target.DEPARTMENT) {
                long members = people.stream()
                        .filter(p -> p.enabled() && a.getDepartmentId().equals(p.departmentId()))
                        .count();
                detail = OrgText.plural(members, "employee");
            } else {
                PersonRow person = peopleById.get(a.getEmployeeId());
                Department department =
                        person == null || person.departmentId() == null ? null : departments.get(person.departmentId());
                detail = department == null ? "—" : department.getName();
            }
            return new AllocationRow(
                    a.getCode(),
                    a.getTargetType().name(),
                    targetId(a),
                    targetName(a.getTargetType(), a.getEmployeeId(), a.getDepartmentId()),
                    detail,
                    new ShiftRef(shift.getCode(), shift.getName(), shift.getColour()),
                    calendar.shift(shift.getCode()).on(shown).timingText(),
                    a.getValidFrom(),
                    a.getValidUntil(),
                    a.state(today).name(),
                    a.getNote(),
                    a.getCreatedByLabel(),
                    a.getCreatedAt());
        }
    }
}
