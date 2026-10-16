package com.krztechnologies.attend.report.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.report.service.ReportService;
import com.krztechnologies.attend.report.service.ReportViews;
import com.krztechnologies.attend.report.service.ReportViews.Uncovered;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Reports: enrolment coverage and recognition quality. */
@RestController
@RequestMapping("/api/v1/reports")
@Validated
public class ReportController {

    /** The coverage groups an employee not covered can be in. */
    private static final String GROUP = "REENROL|PENDING|NO_FACE|NO_CAMERA|EXEMPT";

    private final ReportService reports;
    private final CsvExportService csv;

    public ReportController(ReportService reports, CsvExportService csv) {
        this.reports = reports;
        this.csv = csv;
    }

    @Operation(summary = "Enrolment coverage: the groups, and the groups by department")
    @RequiresPermission(Permission.REPORTS_VIEW)
    @GetMapping("/coverage")
    public ReportViews.Coverage coverage() {
        return reports.coverage();
    }

    @Operation(
            summary = "The employees the camera does not (fully) cover, paged (sort: name, department, location, "
                    + "face, why; default why)")
    @RequiresPermission(Permission.REPORTS_VIEW)
    @GetMapping("/coverage/employees")
    public PageResponse<Uncovered> uncovered(
            @RequestParam(required = false) @Size(max = 5) List<@Pattern(regexp = GROUP) String> group,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> department,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return reports.uncovered(new ReportService.CoverageFilter(group, department, q), page, size, sort);
    }

    @Operation(summary = "The coverage table's filter options with their counts over every employee not covered")
    @RequiresPermission(Permission.REPORTS_VIEW)
    @GetMapping("/coverage/facets")
    public ReportViews.CoverageFacets coverageFacets() {
        return reports.coverageFacets();
    }

    @Operation(summary = "The employees not covered of the filter as CSV, in the table's order")
    @RequiresPermission({Permission.REPORTS_VIEW, Permission.EVENTS_EXPORT})
    @GetMapping("/coverage/export")
    public ResponseEntity<StreamingResponseBody> exportCoverage(
            @RequestParam(required = false) @Size(max = 5) List<@Pattern(regexp = GROUP) String> group,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 20) String> department,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        List<Column<Uncovered>> columns = List.of(
                new Column<>("Employee", Uncovered::name),
                new Column<>("Department", Uncovered::department),
                new Column<>("Location", Uncovered::location),
                new Column<>("Face", u -> EmployeeDirectory.faceStatusLabel(u.faceStatus())),
                new Column<>("Why not covered", u -> ReportService.GROUP_LABELS.get(u.group())));
        return csv.export(
                "enrolment-coverage",
                "Enrolment coverage",
                columns,
                reports.uncoveredExport(new ReportService.CoverageFilter(group, department, q), sort));
    }

    @Operation(summary = "Recognition quality per day, reviewers' decisions, who to re-enrol (default: last 14 days)")
    @RequiresPermission(Permission.REPORTS_VIEW)
    @GetMapping("/recognition")
    public ReportViews.Recognition recognition(
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return reports.recognition(from, to);
    }
}
