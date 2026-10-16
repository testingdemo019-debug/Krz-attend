package com.krztechnologies.attend.organisation.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.AllocationPlanner.IndividualMode;
import com.krztechnologies.attend.organisation.domain.ShiftAllocation;
import com.krztechnologies.attend.organisation.service.AllocationService;
import com.krztechnologies.attend.organisation.service.OrganisationViews;
import com.krztechnologies.attend.organisation.service.OrganisationViews.AllocationRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.CommitResult;
import com.krztechnologies.attend.organisation.service.OrganisationViews.HistoryEntry;
import com.krztechnologies.attend.organisation.service.OrganisationViews.LookupRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.PlanView;
import com.krztechnologies.attend.rules.service.ShiftCalendar.Target;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Organisation → Shift allocation (R9): the wizard's plan and commit, end, cancel, list, history and lookup. */
@RestController
@RequestMapping("/api/v1/allocations")
@Validated
public class AllocationController {

    private static final String CODE = "^ALC-\\d{4,}$";

    private final AllocationService allocations;
    private final CsvExportService csv;

    public AllocationController(AllocationService allocations, CsvExportService csv) {
        this.allocations = allocations;
        this.csv = csv;
    }

    /**
     * The wizard's choices (Shift → Who → Dates → Impact → Confirm).
     *
     * @param targetIds department codes (DEPARTMENT) or employee ids (EMPLOYEE)
     * @param until last day, null = open-ended
     * @param individualMode members' own allocations of a department target: KEEP (default) or REPLACE
     */
    public record AllocationRequest(
            Target targetType,
            @Size(max = 5000) List<@NotBlank @Size(max = 64) String> targetIds,
            @Size(max = 20) String shiftCode,
            LocalDate from,
            @Nullable LocalDate until,
            @Nullable IndividualMode individualMode,
            @Nullable @Size(max = 1000) String note) {

        AllocationService.Request toRequest() {
            return new AllocationService.Request(targetType, targetIds, shiftCode, from, until, individualMode, note);
        }
    }

    /** @param until the last day on this shift */
    public record EndRequest(LocalDate until) {}

    @Operation(summary = "Plan an allocation: overlapping allocations that end or are cancelled, and the impact")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/plan")
    public PlanView plan(@Valid @RequestBody AllocationRequest request) {
        return allocations.plan(request.toRequest());
    }

    @Operation(summary = "Save exactly the plan (409 ALLOCATION_OVERLAP if allocations changed meanwhile)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping
    public CommitResult commit(@Valid @RequestBody AllocationRequest request) {
        return allocations.commit(request.toRequest());
    }

    @Operation(
            summary = "Allocations (target = department code or employee id; state ACTIVE/SCHEDULED/ENDED/CANCELLED)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping
    public PageResponse<AllocationRow> list(
            @RequestParam(required = false) Set<Target> targetType,
            @RequestParam(required = false) @Size(max = 64) String target,
            @RequestParam(required = false) Set<@Size(max = 20) String> shift,
            @RequestParam(required = false) LocalDate activeOn,
            @RequestParam(required = false) Set<ShiftAllocation.State> state,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return allocations.list(
                new AllocationService.Filter(targetType, target, shift, activeOn, state, q), page, size, sort);
    }

    @Operation(summary = "How many allocations have each state, target type and shift (the list's filter counts)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/facets")
    public OrganisationViews.AllocationFacets facets() {
        return allocations.facets();
    }

    @Operation(summary = "Allocations as CSV (same filters as the list)")
    @RequiresPermission({Permission.SETTINGS_ATTENDANCE, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) Set<Target> targetType,
            @RequestParam(required = false) @Size(max = 64) String target,
            @RequestParam(required = false) Set<@Size(max = 20) String> shift,
            @RequestParam(required = false) LocalDate activeOn,
            @RequestParam(required = false) Set<ShiftAllocation.State> state,
            @RequestParam(required = false) @Size(max = 100) String q) {
        List<Column<AllocationRow>> columns = List.of(
                new Column<>("Code", AllocationRow::code),
                new Column<>("Applies to", AllocationRow::targetName),
                new Column<>("Type", AllocationRow::targetType),
                new Column<>("Shift", a -> a.shift().name()),
                new Column<>("From", AllocationRow::from),
                new Column<>("Until", AllocationRow::until),
                new Column<>("State", AllocationRow::state),
                new Column<>("Created by", AllocationRow::createdBy),
                new Column<>("Note", AllocationRow::note));
        return csv.export(
                "shift-allocations",
                "Shift allocations",
                columns,
                allocations.all(new AllocationService.Filter(targetType, target, shift, activeOn, state, q)));
    }

    @Operation(summary = "End an allocation after a day (today or later)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/end")
    public AllocationRow end(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody EndRequest request) {
        return allocations.end(code, request.until());
    }

    @Operation(summary = "Cancel an allocation that has not started")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/cancel")
    public AllocationRow cancel(@PathVariable @Pattern(regexp = CODE) String code) {
        return allocations.cancel(code);
    }

    @Operation(summary = "Every allocation of one target with its recorded changes")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/history")
    public List<HistoryEntry> history(
            @RequestParam @NotNull Target targetType, @RequestParam @NotBlank @Size(max = 64) String targetId) {
        return allocations.history(targetType, targetId);
    }

    @Operation(summary = "Who works which shift on a date, and why")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/lookup")
    public PageResponse<LookupRow> lookup(
            @RequestParam(required = false) LocalDate day,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return allocations.lookup(day, q, page, size);
    }
}
