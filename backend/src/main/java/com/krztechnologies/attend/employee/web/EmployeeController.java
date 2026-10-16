package com.krztechnologies.attend.employee.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.domain.Employment.EmploymentType;
import com.krztechnologies.attend.employee.domain.Employment.TrackingMode;
import com.krztechnologies.attend.employee.domain.Employment.TrackingReason;
import com.krztechnologies.attend.employee.domain.FaceStatus;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.employee.service.EmployeeService;
import com.krztechnologies.attend.employee.service.EmployeeViews;
import com.krztechnologies.attend.employee.service.EmploymentService;
import com.krztechnologies.attend.employee.service.FaceProfileService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Employees: the list, the profile, the employment editor and bulk actions, and the face profile actions (deactivate,
 * reactivate, erase) with the profile's sensitive files.
 */
@RestController
@RequestMapping("/api/v1/employees")
@Validated
public class EmployeeController {

    private static final String QUICK = "missing-id|needs-reenrol|not-enrolled|missing-employment";
    private static final String TRACKING = "TRACKED|NO_FACE|NO_CAMERA|EXEMPT";

    /** The prototype's consent labels, for the CSV. */
    private static final Map<String, String> CONSENT_LABELS =
            Map.of("GIVEN", "Given", "PENDING", "Pending", "NONE", "Not asked", "WITHDRAWN", "Withdrawn");

    private final EmployeeService employees;
    private final EmploymentService employment;
    private final FaceProfileService faces;
    private final CsvExportService csv;

    public EmployeeController(
            EmployeeService employees, EmploymentService employment, FaceProfileService faces, CsvExportService csv) {
        this.employees = employees;
        this.employment = employment;
        this.faces = faces;
        this.csv = csv;
    }

    /**
     * The employment editor.
     *
     * @param employeeId HRMS Employee ID; empty = none yet
     * @param trackingReason required when not tracked
     * @param version the employment version that was loaded (0 when there was no record)
     */
    public record EmploymentRequest(
            @Nullable @Size(max = 32) String employeeId,
            @Nullable @Size(max = 20) String departmentCode,
            @Nullable @Size(max = 200) String designation,
            @Nullable EmploymentType employmentType,
            @Nullable @Size(max = 20) String locationCode,
            @Nullable UUID managerId,
            @Nullable LocalDate joinDate,
            @Nullable LocalDate endDate,
            @Nullable TrackingMode trackingMode,
            @Nullable TrackingReason trackingReason,
            @NotNull Integer version) {}

    /**
     * A bulk action on selected employees.
     *
     * @param action {@code set-department}, {@code allocate-shift} or {@code set-tracking}
     * @param departmentCode set-department
     * @param trackingMode set-tracking (with {@code trackingReason} when not tracked)
     * @param shiftCode allocate-shift, with {@code from}, optional {@code until} and {@code note}
     * @param preview set-tracking only: list who would be skipped and change nothing
     */
    public record BulkRequest(
            @NotEmpty @Size(max = 500) List<@NotNull UUID> ids,

            @NotNull @Pattern(regexp = "set-department|allocate-shift|set-tracking")
            String action,

            @Nullable @Size(max = 20) String departmentCode,
            @Nullable TrackingMode trackingMode,
            @Nullable TrackingReason trackingReason,
            @Nullable @Size(max = 20) String shiftCode,
            @Nullable LocalDate from,
            @Nullable LocalDate until,
            @Nullable @Size(max = 500) String note,
            @Nullable Boolean preview) {}

    /** @param note required when the reason is "Other" */
    public record DeactivateRequest(
            @NotNull @Size(max = 100) String reason,
            @Nullable @Size(max = 500) String note) {}

    /**
     * @param confirm the typed confirmation, exactly {@code ERASE}
     * @param note required when the reason is "Other"
     */
    public record EraseRequest(
            @NotNull @Pattern(regexp = "ERASE", message = "Type ERASE to confirm.")
            String confirm,

            @NotNull @Size(max = 100) String reason,
            @Nullable @Size(max = 500) String note) {}

    @Operation(
            summary = "Employees with filters, paging and sorting (e-mails only with people.view.sensitive); also the"
                    + " employee pickers (review assignment, shift allocation)")
    @RequiresPermission(
            value = {Permission.PEOPLE_VIEW, Permission.REVIEW_RESOLVE, Permission.SETTINGS_ATTENDANCE},
            anyOf = true)
    @GetMapping
    public PageResponse<EmployeeViews.ListItem> list(
            @RequestParam(required = false) @Pattern(regexp = QUICK) String filter,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> department,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> location,
            @RequestParam(required = false) @Size(max = 6) List<FaceStatus> face,
            @RequestParam(required = false) @Size(max = 4) List<@Pattern(regexp = TRACKING) String> tracking,
            @RequestParam(required = false) @Size(max = 3) List<EmploymentType> type,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> shift,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "has|missing") String> employeeId,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "enabled|disabled") String> status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return employees.list(
                filter(filter, department, location, face, tracking, type, shift, employeeId, status, q),
                page,
                size,
                sort);
    }

    @Operation(summary = "The list's filter options with their counts over every employee")
    @RequiresPermission(Permission.PEOPLE_VIEW)
    @GetMapping("/facets")
    public EmployeeViews.Facets facets() {
        return employees.facets();
    }

    @Operation(summary = "The employees of the filter as CSV, in the list's order")
    @RequiresPermission({Permission.PEOPLE_VIEW, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Pattern(regexp = QUICK) String filter,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> department,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> location,
            @RequestParam(required = false) @Size(max = 6) List<FaceStatus> face,
            @RequestParam(required = false) @Size(max = 4) List<@Pattern(regexp = TRACKING) String> tracking,
            @RequestParam(required = false) @Size(max = 3) List<EmploymentType> type,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> shift,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "has|missing") String> employeeId,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "enabled|disabled") String> status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        List<Column<EmployeeViews.ListItem>> columns = List.of(
                new Column<>("Employee", EmployeeViews.ListItem::name),
                new Column<>("Employee ID", EmployeeViews.ListItem::employeeId),
                new Column<>(
                        "Department",
                        e -> e.department() == null ? null : e.department().name()),
                new Column<>("Designation", EmployeeViews.ListItem::designation),
                new Column<>(
                        "Location",
                        e -> e.location() == null ? null : e.location().name()),
                new Column<>(
                        "Type",
                        e -> e.employmentType() == null
                                ? null
                                : EmploymentType.valueOf(e.employmentType()).label()),
                new Column<>(
                        "Shift today",
                        e -> e.shiftToday() == null ? null : e.shiftToday().name()),
                new Column<>("Face", e -> EmployeeDirectory.faceStatusLabel(e.faceStatus())),
                new Column<>("Consent", e -> CONSENT_LABELS.get(e.consentStatus())),
                new Column<>("Tracking", EmployeeViews.ListItem::trackingLabel),
                new Column<>("Joined", EmployeeViews.ListItem::joinDate));
        var rows = employees.export(
                filter(filter, department, location, face, tracking, type, shift, employeeId, status, q), sort);
        return csv.export("employees", "Employees", columns, rows);
    }

    private static EmployeeService.ListFilter filter(
            String quick,
            List<String> departments,
            List<String> locations,
            List<FaceStatus> faces,
            List<String> trackings,
            List<EmploymentType> types,
            List<String> shifts,
            List<String> employeeIds,
            List<String> accounts,
            String q) {
        return new EmployeeService.ListFilter(
                quick,
                departments,
                locations,
                faces == null ? null : faces.stream().map(Enum::name).toList(),
                trackings,
                types == null ? null : types.stream().map(Enum::name).toList(),
                shifts,
                employeeIds,
                accounts,
                q);
    }

    @Operation(
            summary = "The profile: identity, employment, shift today, face profile, consent, last 14 days, activity")
    @RequiresPermission(Permission.PEOPLE_VIEW)
    @GetMapping("/{id}")
    public EmployeeViews.Profile profile(@PathVariable UUID id) {
        return employees.profile(id);
    }

    @Operation(summary = "The shift the employee would work today in a department (the employment editor)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/{id}/shift")
    public EmployeeViews.ShiftToday shiftWithDepartment(
            @PathVariable UUID id, @RequestParam(required = false) @Size(max = 20) String department) {
        return employees.shiftWithDepartment(id, department);
    }

    @Operation(summary = "Save employment details (R5 tracking checks, Employee ID pattern and uniqueness)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/{id}/employment")
    public EmploymentService.Saved updateEmployment(
            @PathVariable UUID id, @Valid @RequestBody EmploymentRequest request) {
        return employment.update(
                id,
                new EmploymentService.Input(
                        request.employeeId(),
                        request.departmentCode(),
                        request.designation(),
                        request.employmentType(),
                        request.locationCode(),
                        request.managerId(),
                        request.joinDate(),
                        request.endDate(),
                        request.trackingMode(),
                        request.trackingReason(),
                        request.version()));
    }

    @Operation(summary = "Change department, allocate a shift or set tracking for selected employees")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/bulk")
    public EmployeeViews.BulkResult bulk(@Valid @RequestBody BulkRequest request) {
        var action = switch (request.action()) {
            case "set-department" -> EmploymentService.BulkAction.SET_DEPARTMENT;
            case "allocate-shift" -> EmploymentService.BulkAction.ALLOCATE_SHIFT;
            default -> EmploymentService.BulkAction.SET_TRACKING;
        };
        return employment.bulk(new EmploymentService.Bulk(
                request.ids(),
                action,
                request.departmentCode(),
                request.trackingMode(),
                request.trackingReason(),
                request.shiftCode(),
                request.from(),
                request.until(),
                request.note(),
                Boolean.TRUE.equals(request.preview())));
    }

    @Operation(summary = "Deactivate the face profile (templates are kept; tracking becomes Not tracked — No face)")
    @RequiresPermission(Permission.PEOPLE_DEACTIVATE)
    @PostMapping("/{id}/face/deactivate")
    public EmployeeViews.Profile deactivate(@PathVariable UUID id, @Valid @RequestBody DeactivateRequest request) {
        faces.deactivate(id, request.reason(), request.note());
        return employees.profile(id);
    }

    @Operation(summary = "Reactivate a deactivated face profile (enabled account, templates and consent needed)")
    @RequiresPermission(Permission.PEOPLE_DEACTIVATE)
    @PostMapping("/{id}/face/reactivate")
    public EmployeeViews.Profile reactivate(@PathVariable UUID id) {
        faces.reactivate(id);
        return employees.profile(id);
    }

    @Operation(summary = "Erase biometric data permanently (typed confirmation ERASE and a reason)")
    @RequiresPermission(Permission.PEOPLE_ERASE)
    @PostMapping("/{id}/face/erase")
    public EmployeeViews.Profile erase(@PathVariable UUID id, @Valid @RequestBody EraseRequest request) {
        faces.erase(id, request.reason(), request.note());
        return employees.profile(id);
    }

    @Operation(summary = "The signed consent form")
    @RequiresPermission(Permission.PEOPLE_VIEW_SENSITIVE)
    @GetMapping("/{id}/consent/document")
    public ResponseEntity<byte[]> consentDocument(@PathVariable UUID id) {
        var content = faces.consentDocument(id);
        return file(
                content,
                ContentDisposition.attachment().filename(content.fileName()).build());
    }

    @Operation(summary = "A face template's thumbnail")
    @RequiresPermission(Permission.PEOPLE_VIEW_SENSITIVE)
    @GetMapping("/{id}/templates/{templateId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID id, @PathVariable long templateId) {
        var content = faces.thumbnail(id, templateId);
        return file(
                content,
                ContentDisposition.inline().filename(content.fileName()).build());
    }

    /** Consent forms download as attachments (security §7); thumbnails show inline. */
    private static ResponseEntity<byte[]> file(FaceProfileService.FileContent content, ContentDisposition disposition) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .header("Content-Disposition", disposition.toString())
                .body(content.bytes());
    }
}
