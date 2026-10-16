package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.EntryPoint;
import com.krztechnologies.attend.organisation.domain.Location;
import com.krztechnologies.attend.organisation.domain.OrgText;
import com.krztechnologies.attend.organisation.domain.RecordStatus;
import com.krztechnologies.attend.organisation.repository.EntryPointRepository;
import com.krztechnologies.attend.organisation.repository.LocationRepository;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries.CameraCount;
import com.krztechnologies.attend.organisation.service.OrganisationViews.EntryPointView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.LocationView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation → Locations (the new page): sites with their entry points. A location or entry point that enabled
 * cameras or employees still use can't be archived; the message says what uses it. Every change is audited
 * (Organisation).
 */
@Service
public class LocationService {

    private static final CameraCount NO_CAMERAS = new CameraCount(0, 0);

    private final LocationRepository locations;
    private final EntryPointRepository entryPoints;
    private final OrganisationQueries queries;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final IdGenerator ids;

    public LocationService(
            LocationRepository locations,
            EntryPointRepository entryPoints,
            OrganisationQueries queries,
            AuditService audit,
            CurrentUser currentUser,
            IdGenerator ids) {
        this.locations = locations;
        this.entryPoints = entryPoints;
        this.queries = queries;
        this.audit = audit;
        this.currentUser = currentUser;
        this.ids = ids;
    }

    /** @param status null = all */
    @Transactional(readOnly = true)
    public PageResponse<LocationView> list(
            String q, Collection<RecordStatus> status, Integer page, Integer size, String sort) {
        return Pages.of(
                all(q, status),
                page,
                size,
                sort,
                Map.of(
                        "code", Pages.text(LocationView::code),
                        "name", Pages.text(LocationView::name),
                        "employees", Comparator.comparingLong(LocationView::employees),
                        "cameras", Comparator.comparingInt(LocationView::cameras),
                        "status", Pages.text(LocationView::status)),
                Sort.by("name"));
    }

    /** Every matching location (list and export). */
    @Transactional(readOnly = true)
    public List<LocationView> all(String q, Collection<RecordStatus> status) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Usage usage = usage();
        return locations.findAll().stream()
                .filter(l -> status == null || status.isEmpty() || status.contains(l.getStatus()))
                .filter(l -> needle.isEmpty()
                        || (l.getCode() + " " + l.getName() + " " + (l.getAddress() == null ? "" : l.getAddress()))
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .map(l -> view(l, usage))
                .toList();
    }

    /** Every location by status (the list's filter counts). */
    @Transactional(readOnly = true)
    public OrganisationViews.StatusFacets facets() {
        return new OrganisationViews.StatusFacets(
                Pages.count(locations.findAll(), l -> l.getStatus().name(), Pages.statuses()));
    }

    @Transactional(readOnly = true)
    public LocationView get(String code) {
        return view(find(code), usage());
    }

    /** Basics, then the entry points of the two-step editor, in one go. */
    @Transactional
    public LocationView create(String code, String name, String address, List<String> entryPointNames) {
        String cleanCode = Form.clean(code);
        String cleanName = Form.clean(name);
        String cleanAddress = Form.clean(address);
        Form form = new Form();
        checkBasics(form, cleanCode, cleanName, cleanAddress);
        form.check(cleanCode != null && locations.existsByCode(cleanCode), "code", "This code is already used.");
        form.check(
                cleanName != null && locations.existsByNameIgnoreCase(cleanName),
                "name",
                "Another location has this name.");
        List<String> names = entryPointNames == null
                ? List.of()
                : entryPointNames.stream().map(Form::clean).toList();
        checkEntryPointNames(form, names);
        form.throwIfInvalid();
        Location location = locations.save(new Location(cleanCode, cleanName, cleanAddress, currentUser.employeeId()));
        for (String epName : names) {
            entryPoints.save(new EntryPoint(ids.next(CodeType.ENTRY_POINT), location.getId(), epName));
        }
        audit.record(
                AuditCategory.ORGANISATION,
                "Created location",
                location.getName(),
                null,
                location.getCode() + (names.isEmpty() ? "" : " · " + OrgText.plural(names.size(), "entry point")),
                null);
        return view(location, usage());
    }

    @Transactional
    public LocationView update(String code, String newCode, String name, String address, int version) {
        Location location = find(code);
        if (location.getVersion() != version) {
            throw Form.stale();
        }
        String cleanCode = Form.clean(newCode);
        String cleanName = Form.clean(name);
        String cleanAddress = Form.clean(address);
        Form form = new Form();
        checkBasics(form, cleanCode, cleanName, cleanAddress);
        form.check(
                cleanCode != null && !cleanCode.equals(location.getCode()) && locations.existsByCode(cleanCode),
                "code",
                "This code is already used.");
        form.check(
                cleanName != null
                        && !cleanName.equalsIgnoreCase(location.getName())
                        && locations.existsByNameIgnoreCase(cleanName),
                "name",
                "Another location has this name.");
        form.throwIfInvalid();
        String before = summary(location);
        location.update(cleanCode, cleanName, cleanAddress);
        audit.record(AuditCategory.ORGANISATION, "Edited location", cleanName, before, summary(location), null);
        return view(location, usage());
    }

    /** Refused while enabled cameras or employees use the location. */
    @Transactional
    public LocationView archive(String code) {
        Location location = find(code);
        if (!location.isActive()) {
            return view(location, usage());
        }
        Usage usage = usage();
        LocationView current = view(location, usage);
        int enabledCameras = current.entryPoints().stream()
                .mapToInt(EntryPointView::enabledCameras)
                .sum();
        List<String> users = new ArrayList<>();
        if (enabledCameras > 0) {
            users.add(OrgText.plural(enabledCameras, "enabled camera"));
        }
        if (current.employees() > 0) {
            users.add(OrgText.plural(current.employees(), "employee"));
        }
        if (!users.isEmpty()) {
            throw new Errors.Conflict(
                    "LOCATION_IN_USE",
                    location.getName() + " is used by " + String.join(" and ", users)
                            + " — move them to another location first.");
        }
        location.setStatus(RecordStatus.ARCHIVED);
        audit.record(
                AuditCategory.ORGANISATION, "Deactivated location", location.getName(), "Active", "Inactive", null);
        return view(location, usage);
    }

    @Transactional
    public LocationView restore(String code) {
        Location location = find(code);
        if (!location.isActive()) {
            location.setStatus(RecordStatus.ACTIVE);
            audit.record(
                    AuditCategory.ORGANISATION, "Reactivated location", location.getName(), "Inactive", "Active", null);
        }
        return view(location, usage());
    }

    @Transactional
    public LocationView addEntryPoint(String code, String name) {
        Location location = activeLocation(code);
        String cleanName = Form.clean(name);
        Form form = new Form();
        checkEntryPointNames(form, Collections.singletonList(cleanName));
        form.check(
                cleanName != null && entryPoints.existsByLocationIdAndNameIgnoreCase(location.getId(), cleanName),
                "name",
                "This location already has an entry point with this name.");
        form.throwIfInvalid();
        EntryPoint entryPoint =
                entryPoints.save(new EntryPoint(ids.next(CodeType.ENTRY_POINT), location.getId(), cleanName));
        audit.record(
                AuditCategory.ORGANISATION,
                "Added entry point",
                location.getName() + " · " + cleanName,
                null,
                entryPoint.getCode(),
                null);
        return view(location, usage());
    }

    @Transactional
    public LocationView renameEntryPoint(String code, String entryPointCode, String name, int version) {
        Location location = activeLocation(code);
        EntryPoint entryPoint = findEntryPoint(location, entryPointCode);
        if (entryPoint.getVersion() != version) {
            throw Form.stale();
        }
        String cleanName = Form.clean(name);
        Form form = new Form();
        checkEntryPointNames(form, Collections.singletonList(cleanName));
        form.check(
                cleanName != null
                        && !cleanName.equalsIgnoreCase(entryPoint.getName())
                        && entryPoints.existsByLocationIdAndNameIgnoreCase(location.getId(), cleanName),
                "name",
                "This location already has an entry point with this name.");
        form.throwIfInvalid();
        String before = entryPoint.getName();
        entryPoint.rename(cleanName);
        audit.record(
                AuditCategory.ORGANISATION,
                "Renamed entry point",
                location.getName() + " · " + entryPoint.getCode(),
                before,
                cleanName,
                null);
        return view(location, usage());
    }

    /** Refused while an enabled camera is mounted there. */
    @Transactional
    public LocationView archiveEntryPoint(String code, String entryPointCode) {
        Location location = find(code);
        EntryPoint entryPoint = findEntryPoint(location, entryPointCode);
        if (entryPoint.isActive()) {
            int enabled = queries.camerasByEntryPoint()
                    .getOrDefault(entryPoint.getId(), NO_CAMERAS)
                    .enabled();
            if (enabled > 0) {
                throw new Errors.Conflict(
                        "ENTRY_POINT_IN_USE",
                        entryPoint.getName() + " has " + OrgText.plural(enabled, "enabled camera")
                                + " — move or disable it first.");
            }
            entryPoint.archive();
            audit.record(
                    AuditCategory.ORGANISATION,
                    "Archived entry point",
                    location.getName() + " · " + entryPoint.getName(),
                    "Active",
                    "Inactive",
                    null);
        }
        return view(location, usage());
    }

    private Location find(String code) {
        return locations.findByCode(code).orElseThrow(() -> new Errors.NotFound("Location not found."));
    }

    private Location activeLocation(String code) {
        Location location = find(code);
        if (!location.isActive()) {
            throw new Errors.Conflict(
                    "LOCATION_ARCHIVED",
                    location.getName() + " is inactive. Reactivate it to change its entry points.");
        }
        return location;
    }

    private EntryPoint findEntryPoint(Location location, String entryPointCode) {
        return entryPoints
                .findByCode(entryPointCode)
                .filter(e -> e.getLocationId().equals(location.getId()))
                .orElseThrow(() -> new Errors.NotFound("Entry point not found."));
    }

    private static void checkBasics(Form form, String code, String name, String address) {
        if (form.check(code == null, "code", "Enter a code.")) {
            form.check(!Form.SHORT_CODE.matcher(code).matches(), "code", "Use 2–6 capital letters or digits.");
        }
        if (form.check(name == null, "name", "Enter a name.")) {
            form.check(name.length() < 2 || name.length() > 80, "name", "Use 2–80 characters.");
        }
        form.check(address != null && address.length() > 300, "address", "Use at most 300 characters.");
    }

    private static void checkEntryPointNames(Form form, List<String> names) {
        for (String name : names) {
            if (form.check(name == null, "entryPoints", "Enter a name for every entry point.")) {
                form.check(name.length() < 2 || name.length() > 80, "entryPoints", "Use 2–80 characters.");
            }
        }
        long distinct = names.stream()
                .filter(Objects::nonNull)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        form.check(
                distinct < names.stream().filter(Objects::nonNull).count(),
                "entryPoints",
                "Give every entry point its own name.");
    }

    private static String summary(Location l) {
        return l.getName() + " · " + l.getCode() + " · " + (l.getAddress() == null ? "—" : l.getAddress());
    }

    /** Cameras and enabled employees per location, read once per request. */
    private record Usage(
            Map<Long, List<EntryPoint>> entryPoints, Map<Long, CameraCount> cameras, Map<Long, Long> employees) {}

    private Usage usage() {
        Map<Long, List<EntryPoint>> byLocation = entryPoints.findAll().stream()
                .sorted(Comparator.comparing(EntryPoint::getCode))
                .collect(Collectors.groupingBy(EntryPoint::getLocationId));
        Map<Long, Long> employees = queries.people().stream()
                .filter(p -> p.enabled() && p.locationId() != null)
                .collect(Collectors.groupingBy(OrganisationQueries.PersonRow::locationId, Collectors.counting()));
        return new Usage(byLocation, queries.camerasByEntryPoint(), employees);
    }

    private static LocationView view(Location l, Usage usage) {
        List<EntryPointView> points = usage.entryPoints().getOrDefault(l.getId(), List.of()).stream()
                .map(e -> {
                    CameraCount count = usage.cameras().getOrDefault(e.getId(), NO_CAMERAS);
                    return new EntryPointView(
                            e.getCode(),
                            e.getName(),
                            e.getStatus().name(),
                            count.total(),
                            count.enabled(),
                            e.getVersion());
                })
                .toList();
        return new LocationView(
                l.getCode(),
                l.getName(),
                l.getAddress(),
                l.getStatus().name(),
                l.getSource(),
                points,
                points.stream().mapToInt(EntryPointView::cameras).sum(),
                points.stream().anyMatch(p -> p.enabledCameras() > 0),
                usage.employees().getOrDefault(l.getId(), 0L),
                l.getCreatedAt(),
                l.getVersion());
    }
}
