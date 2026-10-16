package com.krztechnologies.attend.organisation.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.RecordStatus;
import com.krztechnologies.attend.organisation.service.LocationService;
import com.krztechnologies.attend.organisation.service.OrganisationViews;
import com.krztechnologies.attend.organisation.service.OrganisationViews.LocationView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Organisation → Locations (new page): locations and their entry points. */
@RestController
@RequestMapping("/api/v1/locations")
@Validated
public class LocationController {

    static final String CODE = "^[A-Z0-9]{2,6}$";
    private static final String ENTRY_POINT_CODE = "^EP-\\d{2,}$";

    private final LocationService locations;
    private final CsvExportService csv;

    public LocationController(LocationService locations, CsvExportService csv) {
        this.locations = locations;
        this.csv = csv;
    }

    /** @param entryPoints names of the entry points to create with it (the editor's second step) */
    public record CreateLocationRequest(
            @Size(max = 20) String code,
            @Size(max = 200) String name,
            @Nullable @Size(max = 1000) String address,
            @Nullable @Size(max = 50) List<@Size(max = 200) String> entryPoints) {}

    public record UpdateLocationRequest(
            @Size(max = 20) String code,
            @Size(max = 200) String name,
            @Nullable @Size(max = 1000) String address,
            @NotNull Integer version) {}

    public record EntryPointRequest(@Size(max = 200) String name) {}

    public record RenameEntryPointRequest(
            @Size(max = 200) String name, @NotNull Integer version) {}

    @Operation(summary = "Locations with entry points, cameras and employees")
    @RequiresPermission(
            value = {Permission.SETTINGS_ATTENDANCE, Permission.SETTINGS_CAMERAS},
            anyOf = true)
    @GetMapping
    public PageResponse<LocationView> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) List<RecordStatus> status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return locations.list(q, status, page, size, sort);
    }

    @Operation(summary = "How many locations have each status (the list's filter counts)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/facets")
    public OrganisationViews.StatusFacets facets() {
        return locations.facets();
    }

    @Operation(summary = "Locations as CSV (same filters as the list)")
    @RequiresPermission({Permission.SETTINGS_ATTENDANCE, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) List<RecordStatus> status) {
        List<Column<LocationView>> columns = List.of(
                new Column<>("Code", LocationView::code),
                new Column<>("Name", LocationView::name),
                new Column<>("Address", LocationView::address),
                new Column<>("Entry points", l -> l.entryPoints().size()),
                new Column<>("Cameras", LocationView::cameras),
                new Column<>("Employees", LocationView::employees),
                new Column<>("Status", LocationView::status));
        return csv.export("locations", "Locations", columns, locations.all(q, status));
    }

    @Operation(summary = "Create a location, optionally with its entry points")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping
    public LocationView create(@Valid @RequestBody CreateLocationRequest request) {
        return locations.create(request.code(), request.name(), request.address(), request.entryPoints());
    }

    @Operation(summary = "A location with its entry points")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/{code}")
    public LocationView get(@PathVariable @Pattern(regexp = CODE) String code) {
        return locations.get(code);
    }

    @Operation(summary = "Edit a location's code, name and address")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/{code}")
    public LocationView update(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody UpdateLocationRequest request) {
        return locations.update(code, request.code(), request.name(), request.address(), request.version());
    }

    @Operation(summary = "Deactivate a location (refused while enabled cameras or employees use it)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/archive")
    public LocationView archive(@PathVariable @Pattern(regexp = CODE) String code) {
        return locations.archive(code);
    }

    @Operation(summary = "Reactivate a location")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/restore")
    public LocationView restore(@PathVariable @Pattern(regexp = CODE) String code) {
        return locations.restore(code);
    }

    @Operation(summary = "Add an entry point (EP-nn)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/entry-points")
    public LocationView addEntryPoint(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody EntryPointRequest request) {
        return locations.addEntryPoint(code, request.name());
    }

    @Operation(summary = "Rename an entry point")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/{code}/entry-points/{entryPointCode}")
    public LocationView renameEntryPoint(
            @PathVariable @Pattern(regexp = CODE) String code,
            @PathVariable @Pattern(regexp = ENTRY_POINT_CODE) String entryPointCode,
            @Valid @RequestBody RenameEntryPointRequest request) {
        return locations.renameEntryPoint(code, entryPointCode, request.name(), request.version());
    }

    @Operation(summary = "Archive an entry point (refused while an enabled camera is mounted there)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/{code}/entry-points/{entryPointCode}/archive")
    public LocationView archiveEntryPoint(
            @PathVariable @Pattern(regexp = CODE) String code,
            @PathVariable @Pattern(regexp = ENTRY_POINT_CODE) String entryPointCode) {
        return locations.archiveEntryPoint(code, entryPointCode);
    }
}
