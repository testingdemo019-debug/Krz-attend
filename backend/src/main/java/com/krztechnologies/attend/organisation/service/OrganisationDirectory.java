package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.organisation.domain.Department;
import com.krztechnologies.attend.organisation.domain.EntryPoint;
import com.krztechnologies.attend.organisation.domain.Holiday;
import com.krztechnologies.attend.organisation.domain.Location;
import com.krztechnologies.attend.organisation.domain.OrgText;
import com.krztechnologies.attend.organisation.domain.Shift;
import com.krztechnologies.attend.organisation.domain.ShiftVersion;
import com.krztechnologies.attend.organisation.repository.DepartmentRepository;
import com.krztechnologies.attend.organisation.repository.EntryPointRepository;
import com.krztechnologies.attend.organisation.repository.HolidayRepository;
import com.krztechnologies.attend.organisation.repository.LocationRepository;
import com.krztechnologies.attend.organisation.repository.ShiftAllocationRepository;
import com.krztechnologies.attend.organisation.repository.ShiftRepository;
import com.krztechnologies.attend.organisation.repository.ShiftVersionRepository;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules read from the organisation: departments, locations and entry points by id or code, and the shift
 * calendar (R1–R4) built from the saved shifts, versions, allocations and holidays plus the attendance rules' absent
 * cutoff. The calendar is a snapshot: build a new one after organisation data changes.
 */
@Service
public class OrganisationDirectory {

    private final DepartmentRepository departments;
    private final LocationRepository locations;
    private final EntryPointRepository entryPoints;
    private final ShiftRepository shifts;
    private final ShiftVersionRepository versions;
    private final ShiftAllocationRepository allocations;
    private final HolidayRepository holidays;
    private final SettingsService settings;
    private final TimeService time;

    public OrganisationDirectory(
            DepartmentRepository departments,
            LocationRepository locations,
            EntryPointRepository entryPoints,
            ShiftRepository shifts,
            ShiftVersionRepository versions,
            ShiftAllocationRepository allocations,
            HolidayRepository holidays,
            SettingsService settings,
            TimeService time) {
        this.departments = departments;
        this.locations = locations;
        this.entryPoints = entryPoints;
        this.shifts = shifts;
        this.versions = versions;
        this.allocations = allocations;
        this.holidays = holidays;
        this.settings = settings;
        this.time = time;
    }

    /** A department as other modules see it. */
    public record DepartmentInfo(long id, String code, String name, boolean active) {}

    /** A location as other modules see it. */
    public record LocationInfo(long id, String code, String name, boolean active) {}

    /** An entry point with its location. */
    public record EntryPointInfo(long id, String code, String name, long locationId, boolean active) {}

    @Transactional(readOnly = true)
    public Optional<DepartmentInfo> department(long id) {
        return departments.findById(id).map(OrganisationDirectory::info);
    }

    @Transactional(readOnly = true)
    public Optional<DepartmentInfo> department(String code) {
        return departments.findByCode(code).map(OrganisationDirectory::info);
    }

    @Transactional(readOnly = true)
    public Optional<LocationInfo> location(long id) {
        return locations.findById(id).map(OrganisationDirectory::info);
    }

    @Transactional(readOnly = true)
    public Optional<LocationInfo> location(String code) {
        return locations.findByCode(code).map(OrganisationDirectory::info);
    }

    @Transactional(readOnly = true)
    public Optional<EntryPointInfo> entryPoint(long id) {
        return entryPoints.findById(id).map(OrganisationDirectory::info);
    }

    @Transactional(readOnly = true)
    public Optional<EntryPointInfo> entryPoint(String code) {
        return entryPoints.findByCode(code).map(OrganisationDirectory::info);
    }

    /** The shift calendar over the current organisation data (all shifts, live allocations and holidays). */
    @Transactional(readOnly = true)
    public ShiftCalendar calendar() {
        List<Shift> allShifts = shifts.findAll();
        Map<Long, List<ShiftVersion>> versionsByShift =
                versions.findAll().stream().collect(Collectors.groupingBy(ShiftVersion::getShiftId));
        Map<Long, String> shiftCodes = allShifts.stream().collect(Collectors.toMap(Shift::getId, Shift::getCode));
        List<ShiftCalendar.Shift> calendarShifts = allShifts.stream()
                .filter(s -> versionsByShift.containsKey(s.getId()))
                .map(s -> new ShiftCalendar.Shift(
                        s.getCode(),
                        versionsByShift.get(s.getId()).stream()
                                .map(v -> terms(s, v))
                                .toList()))
                .toList();
        String defaultCode = allShifts.stream()
                .filter(Shift::isDefault)
                .map(Shift::getCode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No default shift (R1)"));
        List<ShiftCalendar.Allocation> live = allocations.findAll().stream()
                .filter(a -> !a.isCancelled())
                .map(a -> a.toCalendar(shiftCodes.get(a.getShiftId())))
                .toList();
        List<ShiftCalendar.Holiday> calendarHolidays = holidays.findAll().stream()
                .map(h -> new ShiftCalendar.Holiday(
                        h.getCode(), h.getDate(), h.getName(), h.getLocationId(), h.getType() == Holiday.Type.PUBLIC))
                .toList();
        return new ShiftCalendar(
                calendarShifts,
                defaultCode,
                live,
                calendarHolidays,
                time.zone(),
                Duration.ofHours(settings.attendanceRules().absentCutoffHours()));
    }

    /**
     * R1 step 4: "individual allocation since 1 Sep 2026", "from Support department since 1 Sep 2026" or "organisation
     * default".
     */
    @Transactional(readOnly = true)
    public String sourceText(ShiftCalendar.Effective effective) {
        return sourceText(effective, id -> departments.findById(id).map(Department::getName));
    }

    static String sourceText(ShiftCalendar.Effective effective, Function<Long, Optional<String>> departmentName) {
        ShiftCalendar.Allocation allocation = effective.allocation();
        return switch (effective.source()) {
            case EMPLOYEE -> "individual allocation since " + OrgText.date(allocation.from());
            case DEPARTMENT ->
                "from "
                        + departmentName.apply(allocation.departmentId()).orElse("the")
                        + " department since "
                        + OrgText.date(allocation.from());
            case DEFAULT -> "organisation default";
        };
    }

    /** The calendar's view of one saved version. */
    static ShiftTerms terms(Shift shift, ShiftVersion version) {
        return version.rules().toTerms(shift.getCode(), shift.getName(), shift.getColour(), version.getEffectiveFrom());
    }

    /** Every shift by id. */
    Map<Long, Shift> shiftsById() {
        return shifts.findAll().stream().collect(Collectors.toMap(Shift::getId, Function.identity()));
    }

    private static DepartmentInfo info(Department d) {
        return new DepartmentInfo(d.getId(), d.getCode(), d.getName(), d.isActive());
    }

    private static LocationInfo info(Location l) {
        return new LocationInfo(l.getId(), l.getCode(), l.getName(), l.isActive());
    }

    private static EntryPointInfo info(EntryPoint e) {
        return new EntryPointInfo(e.getId(), e.getCode(), e.getName(), e.getLocationId(), e.isActive());
    }
}
