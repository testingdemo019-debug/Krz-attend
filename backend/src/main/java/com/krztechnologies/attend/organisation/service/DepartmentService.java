package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.Department;
import com.krztechnologies.attend.organisation.domain.Location;
import com.krztechnologies.attend.organisation.domain.OrgText;
import com.krztechnologies.attend.organisation.domain.RecordStatus;
import com.krztechnologies.attend.organisation.domain.Shift;
import com.krztechnologies.attend.organisation.domain.ShiftAllocation;
import com.krztechnologies.attend.organisation.repository.DepartmentRepository;
import com.krztechnologies.attend.organisation.repository.LocationRepository;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries.PersonRow;
import com.krztechnologies.attend.organisation.repository.ShiftAllocationRepository;
import com.krztechnologies.attend.organisation.repository.ShiftRepository;
import com.krztechnologies.attend.organisation.service.OrganisationViews.DepartmentAllocation;
import com.krztechnologies.attend.organisation.service.OrganisationViews.DepartmentDetail;
import com.krztechnologies.attend.organisation.service.OrganisationViews.DepartmentRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.MemberView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.MoveResult;
import com.krztechnologies.attend.organisation.service.OrganisationViews.PersonRef;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ShiftRef;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation → Departments: code rules, the head must be a member, moving people in with a summary ("2 from Sales,
 * 1 from IT"), the default shift as a department allocation, deactivation (members move to another department; its
 * allocations end today) and deletion of a department that was never used. Moves go to the employee module through
 * {@link MoveToDepartmentRequested}. Every change is audited (Organisation).
 */
@Service
public class DepartmentService {

    private final DepartmentRepository departments;
    private final ShiftRepository shifts;
    private final ShiftAllocationRepository allocations;
    private final LocationRepository locations;
    private final OrganisationQueries queries;
    private final OrganisationDirectory directory;
    private final AllocationService allocationService;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final ApplicationEventPublisher events;

    public DepartmentService(
            DepartmentRepository departments,
            ShiftRepository shifts,
            ShiftAllocationRepository allocations,
            LocationRepository locations,
            OrganisationQueries queries,
            OrganisationDirectory directory,
            AllocationService allocationService,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            ApplicationEventPublisher events) {
        this.departments = departments;
        this.shifts = shifts;
        this.allocations = allocations;
        this.locations = locations;
        this.queries = queries;
        this.directory = directory;
        this.allocationService = allocationService;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.events = events;
    }

    /** @param status null = all */
    @Transactional(readOnly = true)
    public PageResponse<DepartmentRow> list(
            String q, Collection<RecordStatus> status, Integer page, Integer size, String sort) {
        return Pages.of(
                all(q, status),
                page,
                size,
                sort,
                Map.of(
                        "name", Pages.text(DepartmentRow::name),
                        "code", Pages.text(DepartmentRow::code),
                        "head",
                                Pages.text(
                                        r -> r.head() == null ? null : r.head().displayName()),
                        "employees", Comparator.comparingLong(DepartmentRow::employees),
                        "status", Pages.text(DepartmentRow::status)),
                Sort.by("name"));
    }

    /** Every matching department (list and export). */
    @Transactional(readOnly = true)
    public List<DepartmentRow> all(String q, Collection<RecordStatus> status) {
        Context context = context();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return departments.findAll().stream()
                .filter(d -> status == null || status.isEmpty() || status.contains(d.getStatus()))
                .filter(d -> needle.isEmpty()
                        || (d.getName() + " " + d.getCode() + " " + Objects.toString(d.getDescription(), ""))
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .map(context::row)
                .toList();
    }

    /** Every department by status (the list's filter counts). */
    @Transactional(readOnly = true)
    public OrganisationViews.StatusFacets facets() {
        return new OrganisationViews.StatusFacets(
                Pages.count(departments.findAll(), d -> d.getStatus().name(), Pages.statuses()));
    }

    @Transactional(readOnly = true)
    public DepartmentDetail get(String code) {
        return detail(find(code), context());
    }

    /**
     * Creates a department; the head and {@code memberIds} move into it, and a default shift becomes a department
     * allocation from today.
     *
     * @param shiftCode null = members follow the organisation default
     */
    @Transactional
    public DepartmentDetail create(
            String code, String name, String description, UUID headId, String shiftCode, List<UUID> memberIds) {
        Context context = context();
        String cleanCode = Form.clean(code);
        String cleanName = Form.clean(name);
        String cleanDescription = Form.clean(description);
        Form form = new Form();
        checkBasics(form, cleanCode, cleanName, cleanDescription);
        form.check(cleanCode != null && departments.existsByCode(cleanCode), "code", "This code is already used.");
        form.check(
                cleanName != null && departments.existsByNameIgnoreCase(cleanName),
                "name",
                "Another department has this name.");
        form.check(
                headId != null && context.enabled(headId) == null,
                "headId",
                "Must be a member — they move into this department.");
        Shift shift = null;
        if (shiftCode != null) {
            shift = shifts.findByCode(shiftCode).filter(Shift::isActive).orElse(null);
            form.check(shift == null, "shiftCode", "Choose an active shift.");
        }
        Set<UUID> moving = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
        form.check(
                moving.stream().anyMatch(id -> context.enabled(id) == null),
                "memberIds",
                "Choose enabled employees only.");
        form.throwIfInvalid();
        if (headId != null) {
            moving.add(headId);
        }
        Department department = departments.save(
                new Department(cleanCode, cleanName, cleanDescription, headId, currentUser.employeeId()));
        audit.record(
                AuditCategory.ORGANISATION,
                "Created department",
                department.getName(),
                null,
                department.getCode()
                        + (headId == null
                                ? ""
                                : " · head " + context.enabled(headId).displayName()),
                null);
        if (!moving.isEmpty()) {
            events.publishEvent(
                    new MoveToDepartmentRequested(List.copyOf(moving), department.getId(), department.getName()));
        }
        if (shift != null) {
            allocationService.allocateToNewDepartment(
                    department, shift, "Default shift set when the department was created");
        }
        return detail(department, context());
    }

    /** The head must be a member of this department. */
    @Transactional
    public DepartmentDetail update(
            String code, String newCode, String name, String description, UUID headId, int version) {
        Department department = find(code);
        if (department.getVersion() != version) {
            throw Form.stale();
        }
        Context context = context();
        String cleanCode = Form.clean(newCode);
        String cleanName = Form.clean(name);
        String cleanDescription = Form.clean(description);
        Form form = new Form();
        checkBasics(form, cleanCode, cleanName, cleanDescription);
        form.check(
                cleanCode != null && !cleanCode.equals(department.getCode()) && departments.existsByCode(cleanCode),
                "code",
                "This code is already used.");
        form.check(
                cleanName != null
                        && !cleanName.equalsIgnoreCase(department.getName())
                        && departments.existsByNameIgnoreCase(cleanName),
                "name",
                "Another department has this name.");
        PersonRow head = headId == null ? null : context.enabled(headId);
        form.check(
                headId != null && (head == null || !department.getId().equals(head.departmentId())),
                "headId",
                "Must be a member of this department.");
        form.throwIfInvalid();
        String before = summary(department, context);
        department.update(cleanCode, cleanName, cleanDescription, headId);
        audit.record(
                AuditCategory.ORGANISATION,
                "Edited department",
                department.getName(),
                before,
                summary(department, context),
                null);
        return detail(department, context);
    }

    /**
     * Deactivates a department. Its members (enabled or not) must move to another active department ({@code moveTo},
     * required when it has members); its active allocations end today and scheduled ones are cancelled.
     */
    @Transactional
    public DepartmentDetail archive(String code, String moveTo) {
        Department department = find(code);
        if (!department.isActive()) {
            return detail(department, context());
        }
        Context context = context();
        List<PersonRow> members = context.members(department.getId());
        Department target = null;
        if (!members.isEmpty()) {
            Form form = new Form();
            if (form.check(Form.clean(moveTo) == null, "moveTo", "Choose the department its employees move to.")) {
                target = departments
                        .findByCode(moveTo.trim())
                        .filter(d -> d.isActive() && !d.getId().equals(department.getId()))
                        .orElse(null);
                form.check(target == null, "moveTo", "Choose another active department.");
            }
            form.throwIfInvalid();
            events.publishEvent(new MoveToDepartmentRequested(
                    members.stream().map(PersonRow::id).toList(), target.getId(), target.getName()));
        }
        allocationService.endForDepartment(department);
        department.setStatus(RecordStatus.ARCHIVED);
        audit.record(
                AuditCategory.ORGANISATION,
                "Deactivated department",
                department.getName(),
                "Active",
                target == null
                        ? "Inactive"
                        : "Inactive — " + OrgText.plural(members.size(), "employee") + " moved to " + target.getName(),
                null);
        return detail(department, context());
    }

    @Transactional
    public DepartmentDetail restore(String code) {
        Department department = find(code);
        if (!department.isActive()) {
            department.setStatus(RecordStatus.ACTIVE);
            audit.record(
                    AuditCategory.ORGANISATION,
                    "Reactivated department",
                    department.getName(),
                    "Inactive",
                    "Active",
                    null);
        }
        return detail(department, context());
    }

    /** Only a department that was never used (no members, no allocations) can be deleted. */
    @Transactional
    public void delete(String code) {
        Department department = find(code);
        Context context = context();
        int members = context.members(department.getId()).size();
        long allocationCount =
                allocations.findByDepartmentId(department.getId()).size();
        if (members > 0 || allocationCount > 0) {
            throw new Errors.Conflict(
                    "DEPARTMENT_IN_USE",
                    "In use by " + OrgText.plural(members, "employee") + " and "
                            + OrgText.plural(allocationCount, "allocation") + " — deactivate it instead.");
        }
        departments.delete(department);
        audit.record(
                AuditCategory.ORGANISATION,
                "Deleted department",
                department.getName(),
                department.getCode(),
                null,
                null);
    }

    /** Members (enabled or not), by name; {@code q} matches the name or Employee ID. */
    @Transactional(readOnly = true)
    public PageResponse<MemberView> members(String code, String q, Integer page, Integer size) {
        Department department = find(code);
        Context context = context();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<MemberView> rows = context.members(department.getId()).stream()
                .filter(p -> needle.isEmpty()
                        || (p.displayName() + " " + Objects.toString(p.employeeNumber(), ""))
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .map(context::member)
                .toList();
        return Pages.of(rows, page, size, null, Map.of("name", Pages.text(MemberView::displayName)), Sort.by("name"));
    }

    /** Moves enabled employees into an active department; people already in it are skipped. */
    @Transactional
    public MoveResult moveMembers(String code, List<UUID> employeeIds) {
        Department department = find(code);
        if (!department.isActive()) {
            throw new Errors.Conflict(
                    "DEPARTMENT_ARCHIVED", department.getName() + " is inactive. Reactivate it to add employees.");
        }
        Context context = context();
        Set<UUID> wanted = new LinkedHashSet<>(employeeIds);
        Form form = new Form();
        form.check(wanted.isEmpty(), "employeeIds", "Choose at least one.");
        form.check(
                wanted.stream().anyMatch(id -> context.enabled(id) == null),
                "employeeIds",
                "Choose enabled employees only.");
        form.throwIfInvalid();
        List<PersonRow> moving = wanted.stream()
                .map(context::enabled)
                .filter(p -> !department.getId().equals(p.departmentId()))
                .toList();
        Map<String, Integer> byDepartment = new LinkedHashMap<>();
        for (PersonRow person : moving) {
            String from = person.departmentId() == null ? null : context.departmentName(person.departmentId());
            byDepartment.merge(from == null ? "" : from, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        byDepartment.forEach(
                (name, count) -> parts.add(name.isEmpty() ? count + " without a department" : count + " from " + name));
        if (!moving.isEmpty()) {
            events.publishEvent(new MoveToDepartmentRequested(
                    moving.stream().map(PersonRow::id).toList(), department.getId(), department.getName()));
        }
        return new MoveResult(moving.size(), String.join(", ", parts));
    }

    private Department find(String code) {
        return departments.findByCode(code).orElseThrow(() -> new Errors.NotFound("Department not found."));
    }

    private static void checkBasics(Form form, String code, String name, String description) {
        if (form.check(name == null, "name", "Enter a name.")) {
            form.check(name.length() < 2 || name.length() > 80, "name", "Use 2–80 characters.");
        }
        if (form.check(code == null, "code", "Use 2–6 capital letters or digits.")) {
            form.check(!Form.SHORT_CODE.matcher(code).matches(), "code", "Use 2–6 capital letters or digits.");
        }
        form.check(description != null && description.length() > 500, "description", "Use at most 500 characters.");
    }

    /** "Support · SUP · head Asha Admin" (the prototype's before/after of an edit). */
    private static String summary(Department d, Context context) {
        PersonRow head = d.getHeadId() == null ? null : context.peopleById().get(d.getHeadId());
        return d.getName() + " · " + d.getCode() + " · head " + (head == null ? "—" : head.displayName());
    }

    private DepartmentDetail detail(Department d, Context context) {
        DepartmentRow row = context.row(d);
        List<DepartmentAllocation> mine = allocations.findByDepartmentId(d.getId()).stream()
                .sorted(Comparator.comparing(ShiftAllocation::getValidFrom).reversed())
                .map(a -> new DepartmentAllocation(
                        a.getCode(),
                        context.shiftRef(a.getShiftId()),
                        a.getValidFrom(),
                        a.getValidUntil(),
                        a.state(context.today()).name()))
                .toList();
        List<MemberView> members =
                context.members(d.getId()).stream().map(context::member).toList();
        PersonRow creator =
                d.getCreatedBy() == null ? null : context.peopleById().get(d.getCreatedBy());
        Instant createdAt = d.getCreatedAt();
        return new DepartmentDetail(
                row.code(),
                row.name(),
                row.description(),
                row.head(),
                row.employees(),
                row.shift(),
                row.shiftSince(),
                row.locations(),
                row.status(),
                row.source(),
                creator == null ? "System" : creator.displayName(),
                createdAt,
                mine,
                members,
                members.isEmpty() && mine.isEmpty(),
                row.version());
    }

    private Context context() {
        List<PersonRow> people = queries.people();
        return new Context(
                time.today(),
                directory.calendar(),
                directory.shiftsById(),
                departments.findAll().stream().collect(Collectors.toMap(Department::getId, Department::getName)),
                locations.findAll().stream().collect(Collectors.toMap(Location::getId, Location::getName)),
                people,
                people.stream().collect(Collectors.toMap(PersonRow::id, Function.identity())));
    }

    /** What one request reads once. */
    private record Context(
            LocalDate today,
            ShiftCalendar calendar,
            Map<Long, Shift> shifts,
            Map<Long, String> departmentNames,
            Map<Long, String> locationNames,
            List<PersonRow> people,
            Map<UUID, PersonRow> peopleById) {

        /** The person if they exist and their account is enabled, else null. */
        PersonRow enabled(UUID id) {
            PersonRow person = peopleById.get(id);
            return person != null && person.enabled() ? person : null;
        }

        String departmentName(long id) {
            return departmentNames.get(id);
        }

        List<PersonRow> members(long departmentId) {
            return people.stream()
                    .filter(p -> Long.valueOf(departmentId).equals(p.departmentId()))
                    .toList();
        }

        ShiftRef shiftRef(Long shiftId) {
            Shift shift = shifts.get(shiftId);
            return new ShiftRef(shift.getCode(), shift.getName(), shift.getColour());
        }

        MemberView member(PersonRow p) {
            return new MemberView(
                    p.id(),
                    p.displayName(),
                    p.employeeNumber(),
                    p.designation(),
                    p.locationId() == null ? null : locationNames.get(p.locationId()),
                    p.enabled());
        }

        DepartmentRow row(Department d) {
            List<PersonRow> enabledMembers =
                    members(d.getId()).stream().filter(PersonRow::enabled).toList();
            ShiftCalendar.Allocation allocation = calendar.allocations().stream()
                    .filter(a -> a.target() == ShiftCalendar.Target.DEPARTMENT
                            && d.getId().equals(a.departmentId())
                            && a.activeOn(today))
                    .max(Comparator.comparing(ShiftCalendar.Allocation::from))
                    .orElse(null);
            ShiftCalendar.Shift shift =
                    allocation == null ? calendar.defaultShift() : calendar.shift(allocation.shiftCode());
            var terms = shift.on(today);
            PersonRow head = d.getHeadId() == null ? null : peopleById.get(d.getHeadId());
            return new DepartmentRow(
                    d.getCode(),
                    d.getName(),
                    d.getDescription(),
                    head == null ? null : new PersonRef(head.id(), head.displayName(), head.designation()),
                    enabledMembers.size(),
                    new ShiftRef(terms.shiftCode(), terms.shiftName(), terms.colour()),
                    allocation == null ? null : allocation.from(),
                    enabledMembers.stream()
                            .map(PersonRow::locationId)
                            .filter(Objects::nonNull)
                            .map(locationNames::get)
                            .distinct()
                            .sorted()
                            .toList(),
                    d.getStatus().name(),
                    d.getSource(),
                    d.getVersion());
        }
    }
}
