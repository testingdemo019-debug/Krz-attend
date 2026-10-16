package com.krztechnologies.attend.organisation.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.RecordStatus;
import com.krztechnologies.attend.organisation.domain.ShiftRules;
import com.krztechnologies.attend.organisation.service.OrganisationViews.RecalculationImpact;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ShiftCard;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ShiftDetail;
import com.krztechnologies.attend.organisation.service.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
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

/** Organisation → Shifts: cards, the drawer, the shift wizard (new shift, new version) and the default shift. */
@RestController
@RequestMapping("/api/v1/shifts")
@Validated
public class ShiftController {

    private static final String CODE = "^[A-Z0-9-]{2,8}$";

    private final ShiftService shifts;
    private final CsvExportService csv;

    public ShiftController(ShiftService shifts, CsvExportService csv) {
        this.shifts = shifts;
        this.csv = csv;
    }

    /**
     * The timings and rules of a version (the wizard's Basics and Rules steps); R2 checks answer with 422.
     *
     * @param start {@code HH:mm}
     * @param end {@code HH:mm}; at or before {@code start} = ends the next day
     * @param weeklyOffs weekday numbers 0 (Sunday) … 6 (Saturday)
     */
    public record RulesRequest(
            @NotNull LocalTime start,
            @NotNull LocalTime end,
            @NotNull Integer breakMinutes,
            @NotNull @Size(max = 7) List<@NotNull Integer> weeklyOffs,
            @NotNull Integer lateGraceMinutes,
            @NotNull Integer earlyGraceMinutes,
            @NotNull BigDecimal halfDayBelowHours,
            @NotNull BigDecimal absentBelowHours,
            @NotNull BigDecimal windowBeforeHours,
            @NotNull BigDecimal dayCloseHours) {

        ShiftRules toRules() {
            return new ShiftRules(
                    start,
                    end,
                    breakMinutes,
                    weeklyOffs,
                    lateGraceMinutes,
                    earlyGraceMinutes,
                    halfDayBelowHours,
                    absentBelowHours,
                    windowBeforeHours,
                    dayCloseHours);
        }
    }

    /**
     * @param colour one of the palette colours; null = the first unused one
     * @param effectiveFrom first day of the first version; null = today
     */
    public record CreateShiftRequest(
            @Size(max = 20) String code,
            @Size(max = 200) String name,
            @Nullable @Size(max = 20) String colour,
            @Nullable LocalDate effectiveFrom,
            @NotNull @Valid RulesRequest rules) {}

    public record UpdateShiftRequest(
            @Size(max = 200) String name,
            @Nullable @Size(max = 20) String colour,
            @NotNull Integer version) {}

    /** @param effectiveFrom null = tomorrow */
    public record PreviewRequest(@Nullable LocalDate effectiveFrom) {}

    /**
     * @param effectiveFrom null = tomorrow; today or earlier needs {@code confirmRecalculation}
     * @param note why the rules change (audited)
     */
    public record NewVersionRequest(
            @Nullable LocalDate effectiveFrom,
            @NotNull @Valid RulesRequest rules,
            @Nullable @Size(max = 1000) String note,
            boolean confirmRecalculation) {}

    @Operation(summary = "Shift cards: today's rules, scheduled changes, employees today and allocation counts")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping
    public PageResponse<ShiftCard> list(
            @RequestParam(required = false) RecordStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return shifts.list(status, page, size, sort);
    }

    @Operation(summary = "Shifts as CSV (same filters as the list)")
    @RequiresPermission({Permission.SETTINGS_ATTENDANCE, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) RecordStatus status) {
        List<Column<ShiftCard>> columns = List.of(
                new Column<>("Code", ShiftCard::code),
                new Column<>("Name", ShiftCard::name),
                new Column<>("Status", ShiftCard::status),
                new Column<>("Default", s -> s.defaultShift() ? "Yes" : ""),
                new Column<>("Hours", s -> s.today().timing()),
                new Column<>("Break (min)", s -> s.today().breakMinutes()),
                new Column<>("Weekly offs", s -> s.today().weeklyOffText()),
                new Column<>("Late grace (min)", s -> s.today().lateGraceMinutes()),
                new Column<>("Early grace (min)", s -> s.today().earlyGraceMinutes()),
                new Column<>("Employees today", ShiftCard::employeesToday));
        return csv.export("shifts", "Shifts", columns, shifts.all(status));
    }

    @Operation(summary = "Create a shift with its first version")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping
    public ShiftDetail create(@Valid @RequestBody CreateShiftRequest request) {
        return shifts.create(
                request.code(),
                request.name(),
                request.colour(),
                request.effectiveFrom(),
                request.rules().toRules());
    }

    @Operation(summary = "A shift with its versions and allocations")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/{code}")
    public ShiftDetail get(@PathVariable @Pattern(regexp = CODE) String code) {
        return shifts.get(code);
    }

    @Operation(summary = "Rename a shift or change its colour")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/{code}")
    public ShiftDetail update(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody UpdateShiftRequest request) {
        return shifts.update(code, request.name(), request.colour(), request.version());
    }

    @Operation(summary = "What a new version from a day recalculates (employee-days)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/versions/preview")
    public RecalculationImpact preview(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody PreviewRequest request) {
        return shifts.preview(code, request.effectiveFrom());
    }

    @Operation(summary = "New rules from a day (past days keep their version)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/versions")
    public ShiftDetail addVersion(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody NewVersionRequest request) {
        return shifts.addVersion(
                code,
                request.effectiveFrom(),
                request.rules().toRules(),
                request.note(),
                request.confirmRecalculation());
    }

    @Operation(summary = "Deactivate a shift (never the default, never while allocated)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/archive")
    public ShiftDetail archive(@PathVariable @Pattern(regexp = CODE) String code) {
        return shifts.archive(code);
    }

    @Operation(summary = "Reactivate a shift")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/restore")
    public ShiftDetail restore(@PathVariable @Pattern(regexp = CODE) String code) {
        return shifts.restore(code);
    }

    @Operation(summary = "Make a shift the organisation default")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/make-default")
    public ShiftDetail makeDefault(@PathVariable @Pattern(regexp = CODE) String code) {
        return shifts.makeDefault(code);
    }
}
