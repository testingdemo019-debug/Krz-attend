package com.krztechnologies.attend.organisation.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.RecordStatus;
import com.krztechnologies.attend.organisation.service.DepartmentService;
import com.krztechnologies.attend.organisation.service.OrganisationViews;
import com.krztechnologies.attend.organisation.service.OrganisationViews.DepartmentDetail;
import com.krztechnologies.attend.organisation.service.OrganisationViews.DepartmentRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.MemberView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.MoveResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Organisation → Departments: the list, the drawer, the two-step editor, deactivation and member moves. */
@RestController
@RequestMapping("/api/v1/departments")
@Validated
public class DepartmentController {

    private final DepartmentService departments;
    private final CsvExportService csv;

    public DepartmentController(DepartmentService departments, CsvExportService csv) {
        this.departments = departments;
        this.csv = csv;
    }

    /**
     * @param headId moves into the new department
     * @param shiftCode default shift: a department allocation from today; null = organisation default
     * @param memberIds employees who move into the new department
     */
    public record CreateDepartmentRequest(
            @Size(max = 20) String code,
            @Size(max = 200) String name,
            @Nullable @Size(max = 1000) String description,
            @Nullable UUID headId,
            @Nullable @Size(max = 20) String shiftCode,
            @Nullable @Size(max = 5000) List<@NotNull UUID> memberIds) {}

    /** @param headId must be a member of the department */
    public record UpdateDepartmentRequest(
            @Size(max = 20) String code,
            @Size(max = 200) String name,
            @Nullable @Size(max = 1000) String description,
            @Nullable UUID headId,
            @NotNull Integer version) {}

    /** @param moveTo code of the department its members move to (required when it has members) */
    public record ArchiveDepartmentRequest(
            @Nullable @Size(max = 20) String moveTo) {}

    public record MoveMembersRequest(
            @NotEmpty @Size(max = 5000) List<@NotNull UUID> employeeIds) {}

    @Operation(summary = "Departments with head, enabled members, today's department shift and locations")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping
    public PageResponse<DepartmentRow> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) List<RecordStatus> status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return departments.list(q, status, page, size, sort);
    }

    @Operation(summary = "How many departments have each status (the list's filter counts)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/facets")
    public OrganisationViews.StatusFacets facets() {
        return departments.facets();
    }

    @Operation(summary = "Departments as CSV (same filters as the list)")
    @RequiresPermission({Permission.SETTINGS_ATTENDANCE, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) List<RecordStatus> status) {
        List<Column<DepartmentRow>> columns = List.of(
                new Column<>("Department", DepartmentRow::name),
                new Column<>("Code", DepartmentRow::code),
                new Column<>("Head", d -> d.head() == null ? "" : d.head().displayName()),
                new Column<>("Employees", DepartmentRow::employees),
                new Column<>("Department shift", d -> d.shift().name()),
                new Column<>("Locations", d -> String.join("; ", d.locations())),
                new Column<>("Status", DepartmentRow::status),
                new Column<>("Source", DepartmentRow::source));
        return csv.export("departments", "Departments", columns, departments.all(q, status));
    }

    @Operation(summary = "Create a department (head and members move in; optional default shift from today)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping
    public DepartmentDetail create(@Valid @RequestBody CreateDepartmentRequest request) {
        return departments.create(
                request.code(),
                request.name(),
                request.description(),
                request.headId(),
                request.shiftCode(),
                request.memberIds());
    }

    @Operation(summary = "A department with its allocations and members")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/{code}")
    public DepartmentDetail get(@PathVariable @Pattern(regexp = LocationController.CODE) String code) {
        return departments.get(code);
    }

    @Operation(summary = "Edit a department (the head must be a member)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/{code}")
    public DepartmentDetail update(
            @PathVariable @Pattern(regexp = LocationController.CODE) String code,
            @Valid @RequestBody UpdateDepartmentRequest request) {
        return departments.update(
                code, request.code(), request.name(), request.description(), request.headId(), request.version());
    }

    @Operation(summary = "Deactivate a department: members move to moveTo, its allocations end today")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/archive")
    public DepartmentDetail archive(
            @PathVariable @Pattern(regexp = LocationController.CODE) String code,
            @Valid @RequestBody(required = false) ArchiveDepartmentRequest request) {
        return departments.archive(code, request == null ? null : request.moveTo());
    }

    @Operation(summary = "Reactivate a department")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/restore")
    public DepartmentDetail restore(@PathVariable @Pattern(regexp = LocationController.CODE) String code) {
        return departments.restore(code);
    }

    @Operation(summary = "Delete a department that was never used (no members, no allocations)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = LocationController.CODE) String code) {
        departments.delete(code);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Members of a department (enabled or not)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/{code}/members")
    public PageResponse<MemberView> members(
            @PathVariable @Pattern(regexp = LocationController.CODE) String code,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return departments.members(code, q, page, size);
    }

    @Operation(summary = "Move employees into a department; returns the summary (\"2 from Sales, 1 from IT\")")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/members")
    public MoveResult moveMembers(
            @PathVariable @Pattern(regexp = LocationController.CODE) String code,
            @Valid @RequestBody MoveMembersRequest request) {
        return departments.moveMembers(code, request.employeeIds());
    }
}
