package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.camera.service.CameraCoverage;
import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.domain.Employee;
import com.krztechnologies.attend.employee.domain.Employment;
import com.krztechnologies.attend.employee.domain.Employment.EmploymentType;
import com.krztechnologies.attend.employee.domain.Employment.TrackingMode;
import com.krztechnologies.attend.employee.domain.Employment.TrackingReason;
import com.krztechnologies.attend.employee.domain.FaceProfile;
import com.krztechnologies.attend.employee.repository.EmployeeRepository;
import com.krztechnologies.attend.employee.repository.EmploymentRepository;
import com.krztechnologies.attend.employee.repository.FaceProfileRepository;
import com.krztechnologies.attend.organisation.service.AllocationService;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory.DepartmentInfo;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory.LocationInfo;
import com.krztechnologies.attend.rules.service.TimeFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employment details as edited in KRZ Attend until the HRMS sync exists: the editor (R5 tracking checks, the Employee
 * ID pattern and uniqueness) and the bulk actions of Employees. Every changed field is audited "Changed &lt;field&gt;"
 * with readable before and after values, as the prototype's editor does.
 */
@Service
public class EmploymentService {

    private static final String NONE = "—";

    /** An edit recomputes the last 21 days, as the prototype's editor reconciles the outbox. */
    private static final int EDIT_RECOMPUTE_DAYS = 21;

    private final EmployeeRepository employees;
    private final EmploymentRepository employments;
    private final FaceProfileRepository faceProfiles;
    private final OrganisationDirectory organisation;
    private final AllocationService allocations;
    private final DepartmentMembership membership;
    private final CameraCoverage cameras;
    private final SettingsService settings;
    private final AuditService audit;
    private final EmployeeService views;
    private final ApplicationEventPublisher events;
    private final TimeService time;

    public EmploymentService(
            EmployeeRepository employees,
            EmploymentRepository employments,
            FaceProfileRepository faceProfiles,
            OrganisationDirectory organisation,
            AllocationService allocations,
            DepartmentMembership membership,
            CameraCoverage cameras,
            SettingsService settings,
            AuditService audit,
            EmployeeService views,
            ApplicationEventPublisher events,
            TimeService time) {
        this.employees = employees;
        this.employments = employments;
        this.faceProfiles = faceProfiles;
        this.organisation = organisation;
        this.allocations = allocations;
        this.membership = membership;
        this.cameras = cameras;
        this.settings = settings;
        this.audit = audit;
        this.views = views;
        this.events = events;
        this.time = time;
    }

    /**
     * The employment editor's values.
     *
     * @param employeeId HRMS Employee ID (optional until the HRMS sync exists; stored in upper case)
     * @param trackingReason required when not tracked
     * @param version the {@code employment.version} that was loaded (0 when there was no record)
     */
    public record Input(
            String employeeId,
            String departmentCode,
            String designation,
            EmploymentType employmentType,
            String locationCode,
            UUID managerId,
            LocalDate joinDate,
            LocalDate endDate,
            TrackingMode trackingMode,
            TrackingReason trackingReason,
            int version) {}

    /** The editor's answer: how many fields changed (0 = "No changes to save") and the profile as saved. */
    public record Saved(int changed, EmployeeViews.Profile employee) {}

    /** The bulk actions of Employees. */
    public enum BulkAction {
        SET_DEPARTMENT,
        ALLOCATE_SHIFT,
        SET_TRACKING
    }

    /**
     * A bulk action and its options.
     *
     * @param departmentCode SET_DEPARTMENT: an active department
     * @param trackingMode SET_TRACKING
     * @param trackingReason SET_TRACKING when not tracked
     * @param shiftCode ALLOCATE_SHIFT, with {@code from}, optional {@code until} and {@code note}
     */
    public record Bulk(
            List<UUID> ids,
            BulkAction action,
            String departmentCode,
            TrackingMode trackingMode,
            TrackingReason trackingReason,
            String shiftCode,
            LocalDate from,
            LocalDate until,
            String note,
            boolean preview) {}

    @Transactional
    public Saved update(UUID id, Input input) {
        Employee person = employees.findById(id).orElseThrow(() -> new Errors.NotFound("Employee not found."));
        Optional<Employment> existing = employments.findById(id);
        int currentVersion = existing.map(Employment::getVersion).orElse(0);
        if (input.version() != currentVersion) {
            throw new Errors.Conflict("STALE_VERSION", "Someone else changed this employee. Reload to see the latest.");
        }
        Employment employment = existing.orElseGet(() -> new Employment(id));
        Resolved v = validate(person, employment, input);

        List<String[]> changes = new ArrayList<>();
        change(changes, "Employee ID", employment.getHrmsEmployeeId(), v.employeeId());
        change(
                changes,
                "Department",
                departmentName(employment.getDepartmentId()),
                v.department().name());
        change(changes, "Designation", employment.getDesignation(), v.designation());
        change(
                changes,
                "Employment type",
                employment.getEmploymentType() == null
                        ? null
                        : employment.getEmploymentType().label(),
                input.employmentType().label());
        change(
                changes,
                "Location",
                locationName(employment.getLocationId()),
                v.location().name());
        change(changes, "Manager", managerName(employment.getManagerId()), v.managerName());
        change(changes, "Join date", date(employment.getJoinDate()), date(input.joinDate()));
        change(changes, "Contract end date", date(employment.getEndDate()), date(input.endDate()));
        String trackingBefore = existing.isPresent() ? employment.trackingLabel() : null;
        employment.setHrmsEmployeeId(v.employeeId());
        employment.setDepartmentId(v.department().id());
        employment.setDesignation(v.designation());
        employment.setEmploymentType(input.employmentType());
        employment.setLocationId(v.location().id());
        employment.setManagerId(input.managerId());
        employment.setDates(input.joinDate(), input.endDate());
        employment.setTracking(input.trackingMode(), input.trackingReason());
        change(changes, "Attendance tracking", trackingBefore, employment.trackingLabel());
        if (changes.isEmpty()) {
            return new Saved(0, views.profile(id));
        }
        employments.saveAndFlush(employment);
        for (String[] c : changes) {
            audit.record(
                    AuditCategory.EMPLOYEES,
                    "Changed " + c[0].toLowerCase(Locale.ROOT),
                    person.getDisplayName(),
                    c[1],
                    c[2],
                    null);
        }
        LocalDate today = time.today();
        events.publishEvent(AttendanceAffected.employees(List.of(id), today.minusDays(EDIT_RECOMPUTE_DAYS), today));
        return new Saved(changes.size(), views.profile(id));
    }

    /** Runs a bulk action; people it cannot apply to are listed with the reason (the prototype's "will be skipped"). */
    @Transactional
    public EmployeeViews.BulkResult bulk(Bulk request) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(request.ids()));
        List<Employee> people = employees.findAllById(ids).stream()
                .sorted(Comparator.comparing(Employee::getDisplayName))
                .toList();
        if (people.size() != ids.size()) {
            throw new Errors.NotFound("Some of the selected employees no longer exist. Reload and try again.");
        }
        return switch (request.action()) {
            case SET_DEPARTMENT -> setDepartment(ids, request.departmentCode());
            case ALLOCATE_SHIFT -> {
                var result = allocations.commitForEmployees(
                        ids, request.shiftCode(), request.from(), request.until(), request.note());
                yield new EmployeeViews.BulkResult((int) result.changing(), List.of());
            }
            case SET_TRACKING ->
                setTracking(people, request.trackingMode(), request.trackingReason(), request.preview());
        };
    }

    private EmployeeViews.BulkResult setDepartment(List<UUID> ids, String departmentCode) {
        DepartmentInfo department = Optional.ofNullable(departmentCode)
                .flatMap(organisation::department)
                .filter(DepartmentInfo::active)
                .orElseThrow(() -> invalid("departmentCode", "Choose an active department."));
        return new EmployeeViews.BulkResult(membership.move(ids, department.id(), department.name()), List.of());
    }

    /** @param preview only list who would be skipped (the prototype's "will be skipped"); nothing is saved */
    private EmployeeViews.BulkResult setTracking(
            List<Employee> people, TrackingMode mode, TrackingReason reason, boolean preview) {
        if (mode == null) {
            throw invalid("trackingMode", "Choose tracked or not tracked.");
        }
        if (mode == TrackingMode.NOT_TRACKED && reason == null) {
            throw invalid("trackingReason", "Choose a reason.");
        }
        List<UUID> changed = new ArrayList<>();
        List<EmployeeViews.Skipped> skipped = new ArrayList<>();
        for (Employee person : people) {
            Employment employment =
                    employments.findById(person.getId()).orElseGet(() -> new Employment(person.getId()));
            String problem = trackingProblem(person, employment.getLocationId(), mode);
            if (problem != null) {
                skipped.add(new EmployeeViews.Skipped(person.getId(), person.getDisplayName(), problem));
                continue;
            }
            if (preview) {
                continue;
            }
            String before = employment.trackingLabel();
            employment.setTracking(mode, reason);
            if (!before.equals(employment.trackingLabel())) {
                employments.saveAndFlush(employment);
                audit.record(
                        AuditCategory.EMPLOYEES,
                        "Changed attendance tracking",
                        person.getDisplayName(),
                        before,
                        employment.trackingLabel(),
                        null);
                changed.add(person.getId());
            }
        }
        if (!changed.isEmpty()) {
            LocalDate today = time.today();
            events.publishEvent(AttendanceAffected.employees(changed, today.minusDays(1), today));
        }
        return new EmployeeViews.BulkResult(changed.size(), skipped);
    }

    /** R5: why this person cannot be tracked by camera, or null (the prototype's {@code trackingProblem}). */
    private String trackingProblem(Employee person, Long locationId, TrackingMode mode) {
        if (mode != TrackingMode.TRACKED) {
            return null;
        }
        if (locationId == null) {
            return "No location set";
        }
        if (!cameras.locationHasCamera(locationId)) {
            return locationName(locationId) + " has no camera";
        }
        boolean activeFace = faceProfiles
                .findById(person.getId())
                .map(FaceProfile::getStatus)
                .filter(s -> s.isActive())
                .isPresent();
        if (!activeFace) {
            return "No active face profile — enrol first";
        }
        if (!person.isAccountEnabled()) {
            return "Account disabled";
        }
        return null;
    }

    private record Resolved(
            String employeeId,
            DepartmentInfo department,
            String designation,
            LocationInfo location,
            String managerName) {}

    private Resolved validate(Employee person, Employment employment, Input input) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        String employeeId = clean(input.employeeId());
        if (employeeId != null) {
            employeeId = employeeId.toUpperCase(Locale.ROOT);
            String pattern = settings.general().employeeIdPattern();
            if (!Pattern.compile(pattern).matcher(employeeId).matches()) {
                errors.add(new ErrorResponse.FieldError(
                        "employeeId", "Use the Employee ID format set in Settings → General (" + pattern + ")."));
            } else {
                String id = employeeId;
                employments
                        .findByHrmsEmployeeIdIgnoreCase(id)
                        .filter(other -> !other.getEmployeeId().equals(person.getId()))
                        .flatMap(other -> employees.findById(other.getEmployeeId()))
                        .ifPresent(other -> errors.add(new ErrorResponse.FieldError(
                                "employeeId", id + " already belongs to " + other.getDisplayName() + ".")));
            }
        }
        DepartmentInfo department = Optional.ofNullable(input.departmentCode())
                .flatMap(organisation::department)
                .filter(d -> d.active() || Objects.equals(d.id(), employment.getDepartmentId()))
                .orElse(null);
        if (department == null) {
            errors.add(new ErrorResponse.FieldError("departmentCode", "Choose an active department."));
        }
        String designation = clean(input.designation());
        if (designation == null) {
            errors.add(new ErrorResponse.FieldError("designation", "Enter a designation."));
        } else if (designation.length() > 120) {
            errors.add(new ErrorResponse.FieldError("designation", "Use 120 characters or fewer."));
        }
        if (input.employmentType() == null) {
            errors.add(new ErrorResponse.FieldError("employmentType", "Choose an employment type."));
        }
        LocationInfo location = Optional.ofNullable(input.locationCode())
                .flatMap(organisation::location)
                .orElse(null);
        if (location == null) {
            errors.add(new ErrorResponse.FieldError("locationCode", "Choose a location."));
        }
        String managerName = NONE;
        if (input.managerId() != null) {
            Employee manager = input.managerId().equals(person.getId())
                    ? null
                    : employees
                            .findById(input.managerId())
                            .filter(Employee::isAccountEnabled)
                            .orElse(null);
            if (manager == null) {
                errors.add(
                        new ErrorResponse.FieldError("managerId", "Choose an enabled employee other than this one."));
            } else {
                managerName = manager.getDisplayName();
            }
        }
        if (input.joinDate() == null) {
            errors.add(new ErrorResponse.FieldError("joinDate", "Enter the join date."));
        } else if (input.endDate() != null && input.endDate().isBefore(input.joinDate())) {
            errors.add(new ErrorResponse.FieldError("endDate", "The end date can’t be before the join date."));
        }
        if (input.trackingMode() == null) {
            errors.add(new ErrorResponse.FieldError("trackingMode", "Choose tracked or not tracked."));
        } else if (input.trackingMode() == TrackingMode.NOT_TRACKED && input.trackingReason() == null) {
            errors.add(new ErrorResponse.FieldError("trackingReason", "Choose a reason."));
        } else if (location != null) {
            String problem = trackingProblem(person, location.id(), input.trackingMode());
            if (problem != null) {
                errors.add(new ErrorResponse.FieldError("trackingMode", "Can’t track by camera: " + problem + "."));
            }
        }
        if (!errors.isEmpty()) {
            throw new Errors.BusinessRule("VALIDATION", "Fix the highlighted fields.", errors);
        }
        return new Resolved(employeeId, department, designation, location, managerName);
    }

    private static Errors.BusinessRule invalid(String field, String message) {
        return new Errors.BusinessRule("VALIDATION", message, List.of(new ErrorResponse.FieldError(field, message)));
    }

    private static void change(List<String[]> changes, String label, String before, String after) {
        if (!Objects.equals(blankToEmpty(before), blankToEmpty(after))) {
            changes.add(new String[] {label, before, after});
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String date(LocalDate day) {
        return day == null ? null : TimeFormat.date(day);
    }

    private String departmentName(Long id) {
        return name(id, i -> organisation.department(i).map(DepartmentInfo::name));
    }

    private String locationName(Long id) {
        return name(id, i -> organisation.location(i).map(LocationInfo::name));
    }

    private static String name(Long id, Function<Long, Optional<String>> lookup) {
        return id == null ? null : lookup.apply(id).orElse(NONE);
    }

    private String managerName(UUID id) {
        return id == null
                ? NONE
                : employees.findById(id).map(Employee::getDisplayName).orElse(NONE);
    }
}
