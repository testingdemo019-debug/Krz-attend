package com.krztechnologies.attend.organisation.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.Holiday;
import com.krztechnologies.attend.organisation.service.HolidayService;
import com.krztechnologies.attend.organisation.service.OrganisationViews;
import com.krztechnologies.attend.organisation.service.OrganisationViews.HolidayView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ImportPreview;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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

/** Organisation → Holidays (R4): the year's holidays, the editor and the CSV import with preview. */
@RestController
@RequestMapping("/api/v1/holidays")
@Validated
public class HolidayController {

    private static final String CODE = "^HOL-\\d{4}-\\d{2,}$";

    private final HolidayService holidays;
    private final CsvExportService csv;

    public HolidayController(HolidayService holidays, CsvExportService csv) {
        this.holidays = holidays;
        this.csv = csv;
    }

    /**
     * @param type PUBLIC (default) or OPTIONAL
     * @param locationCode null = all locations
     */
    public record HolidayRequest(
            LocalDate date,
            @Size(max = 300) String name,
            Holiday.@Nullable Type type,
            @Nullable @Size(max = 20) String locationCode) {}

    public record UpdateHolidayRequest(
            LocalDate date,
            @Size(max = 300) String name,
            Holiday.@Nullable Type type,
            @Nullable @Size(max = 20) String locationCode,
            @NotNull Integer version) {}

    /**
     * The CSV text (the browser reads a chosen file and sends its text).
     *
     * @param csv columns Date, Name, Type, Location
     */
    public record CsvRequest(@NotNull @Size(max = 200_000) String csv) {}

    @Operation(summary = "Holidays of a year (location = a location code, or ALL for all-location holidays)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping
    public PageResponse<HolidayView> list(
            @RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
            @RequestParam(required = false) Set<Holiday.Type> type,
            @RequestParam(required = false) Set<@Size(max = 20) String> location,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return holidays.list(new HolidayService.Filter(year, type, location, q), page, size, sort);
    }

    @Operation(summary = "How many holidays of the year have each type and location (the list's filter counts)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/facets")
    public OrganisationViews.HolidayFacets facets(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        return holidays.facets(year);
    }

    @Operation(summary = "Holidays as CSV (same filters as the list)")
    @RequiresPermission({Permission.SETTINGS_ATTENDANCE, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
            @RequestParam(required = false) Set<Holiday.Type> type,
            @RequestParam(required = false) Set<@Size(max = 20) String> location,
            @RequestParam(required = false) @Size(max = 100) String q) {
        List<Column<HolidayView>> columns = List.of(
                new Column<>("Date", HolidayView::date),
                new Column<>("Holiday", HolidayView::name),
                new Column<>("Type", HolidayView::type),
                new Column<>(
                        "Applies to",
                        h -> h.location() == null
                                ? "All locations"
                                : h.location().name()),
                new Column<>("Source", h -> h.sample() ? "Sample" : h.source()));
        return csv.export(
                "holidays", "Holidays", columns, holidays.all(new HolidayService.Filter(year, type, location, q)));
    }

    @Operation(summary = "Add a holiday")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping
    public HolidayView create(@Valid @RequestBody HolidayRequest request) {
        return holidays.create(request.date(), request.name(), request.type(), request.locationCode());
    }

    @Operation(summary = "Edit a holiday (a sample holiday becomes a real one)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/{code}")
    public HolidayView update(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody UpdateHolidayRequest request) {
        return holidays.update(
                code, request.date(), request.name(), request.type(), request.locationCode(), request.version());
    }

    @Operation(summary = "Delete a holiday (the date becomes a normal working day)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = CODE) String code) {
        holidays.delete(code);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Preview a holiday CSV: every row with its result")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/import/preview")
    public ImportPreview preview(@Valid @RequestBody CsvRequest request) {
        return holidays.preview(request.csv());
    }

    @Operation(summary = "Import the ready rows of a holiday CSV")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/import")
    public ImportResult importCsv(@Valid @RequestBody CsvRequest request) {
        return holidays.importCsv(request.csv());
    }
}
