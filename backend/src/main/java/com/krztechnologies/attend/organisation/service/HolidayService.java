package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.id.CodeType;
import com.krztechnologies.attend.common.id.IdGenerator;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.Holiday;
import com.krztechnologies.attend.organisation.domain.HolidayCsv;
import com.krztechnologies.attend.organisation.domain.Location;
import com.krztechnologies.attend.organisation.domain.OrgText;
import com.krztechnologies.attend.organisation.repository.HolidayRepository;
import com.krztechnologies.attend.organisation.repository.LocationRepository;
import com.krztechnologies.attend.organisation.service.OrganisationViews.HolidayView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ImportPreview;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ImportResult;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ImportRow;
import com.krztechnologies.attend.organisation.service.OrganisationViews.LocationRef;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation → Holidays (R4): one holiday per date and location (none = all locations); only PUBLIC holidays change
 * attendance. CSV import with a preview, both through {@link HolidayCsv}. Editing a sample holiday makes it a real one.
 * Every change is audited (Organisation).
 */
@Service
public class HolidayService {

    private static final String DUPLICATE = "There’s already a holiday on this date for these locations.";
    private static final int AUDIT_LIST_LENGTH = 200;

    private static final int RECOMPUTE_DAYS = 21;

    private final HolidayRepository holidays;
    private final LocationRepository locations;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final IdGenerator ids;
    private final ApplicationEventPublisher events;

    public HolidayService(
            HolidayRepository holidays,
            LocationRepository locations,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            IdGenerator ids,
            ApplicationEventPublisher events) {
        this.holidays = holidays;
        this.locations = locations;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.ids = ids;
        this.events = events;
    }

    /**
     * List filters (all optional).
     *
     * @param year null = the current year
     * @param location a location code, or {@code ALL} for holidays of all locations
     */
    /**
     * @param types empty = both
     * @param locations location codes, or {@code ALL} for the holidays of every location; empty = any
     */
    public record Filter(Integer year, Set<Holiday.Type> types, Set<String> locations, String q) {}

    @Transactional(readOnly = true)
    public PageResponse<HolidayView> list(Filter filter, Integer page, Integer size, String sort) {
        return Pages.of(
                all(filter),
                page,
                size,
                sort,
                Map.of(
                        "date", Comparator.comparing(HolidayView::date),
                        "name", Pages.text(HolidayView::name),
                        "type", Pages.text(HolidayView::type)),
                Sort.by("date"));
    }

    /** The public holidays of every location between two days (the Dashboard's working days). */
    @Transactional(readOnly = true)
    public Set<LocalDate> organisationWidePublicHolidays(LocalDate from, LocalDate to) {
        return holidays.findAll().stream()
                .filter(h -> h.getType() == Holiday.Type.PUBLIC && h.getLocationId() == null)
                .map(Holiday::getDate)
                .filter(day -> !day.isBefore(from) && !day.isAfter(to))
                .collect(Collectors.toSet());
    }

    /** Every matching holiday of the year (list and export). */
    @Transactional(readOnly = true)
    public List<HolidayView> all(Filter filter) {
        int year = filter.year() == null ? time.today().getYear() : filter.year();
        Map<Long, Location> byId = locationsById();
        String needle = filter.q() == null ? "" : filter.q().trim().toLowerCase(Locale.ROOT);
        return holidays.findAll().stream()
                .filter(h -> h.getDate().getYear() == year)
                .filter(h -> filter.types() == null
                        || filter.types().isEmpty()
                        || filter.types().contains(h.getType()))
                .filter(h -> filter.locations() == null
                        || filter.locations().isEmpty()
                        || filter.locations().contains(locationValue(h, byId)))
                .filter(h -> needle.isEmpty()
                        || (h.getName() + " " + h.getDate())
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .map(h -> view(h, byId))
                .toList();
    }

    /** The holidays of the year by type and by location (the list's filter counts). */
    @Transactional(readOnly = true)
    public OrganisationViews.HolidayFacets facets(Integer year) {
        int y = year == null ? time.today().getYear() : year;
        Map<Long, Location> byId = locationsById();
        List<Holiday> rows = holidays.findAll().stream()
                .filter(h -> h.getDate().getYear() == y)
                .toList();
        Map<String, String> types = new LinkedHashMap<>();
        for (Holiday.Type type : Holiday.Type.values()) {
            types.put(type.name(), type.label());
        }
        Map<String, String> places = new LinkedHashMap<>();
        places.put("ALL", "All locations");
        byId.values().stream()
                .sorted(Comparator.comparing(l -> l.getName().toLowerCase(Locale.ROOT)))
                .forEach(l -> places.put(l.getCode(), l.getName()));
        return new OrganisationViews.HolidayFacets(
                Pages.count(rows, h -> h.getType().name(), types),
                Pages.count(rows, h -> locationValue(h, byId), places));
    }

    /** A holiday's location filter value: the location's code, or ALL. */
    private static String locationValue(Holiday h, Map<Long, Location> byId) {
        return h.getLocationId() == null ? "ALL" : byId.get(h.getLocationId()).getCode();
    }

    /** @param locationCode null = all locations */
    @Transactional
    public HolidayView create(LocalDate date, String name, Holiday.Type type, String locationCode) {
        Holiday.Type chosen = type == null ? Holiday.Type.PUBLIC : type;
        String cleanName = Form.clean(name);
        Location location = check(null, date, cleanName, locationCode);
        Holiday holiday = new Holiday(
                ids.next(CodeType.HOLIDAY, String.valueOf(date.getYear())),
                date,
                cleanName,
                chosen,
                location == null ? null : location.getId(),
                "LOCAL",
                currentUser.employeeId());
        saveChecked(holiday);
        affected(date, holiday.getLocationId());
        audit.record(
                AuditCategory.ORGANISATION,
                "Added holiday",
                cleanName + " · " + OrgText.date(date),
                null,
                chosen.label() + (location == null ? "" : " · " + location.getName()),
                null);
        return view(holiday, locationsById());
    }

    @Transactional
    public HolidayView update(
            String code, LocalDate date, String name, Holiday.Type type, String locationCode, int version) {
        Holiday holiday = find(code);
        if (holiday.getVersion() != version) {
            throw Form.stale();
        }
        Holiday.Type chosen = type == null ? holiday.getType() : type;
        String cleanName = Form.clean(name);
        Location location = check(holiday, date, cleanName, locationCode);
        String before = summary(holiday);
        affected(holiday.getDate(), holiday.getLocationId());
        holiday.update(date, cleanName, chosen, location == null ? null : location.getId());
        saveChecked(holiday);
        affected(holiday.getDate(), holiday.getLocationId());
        audit.record(AuditCategory.ORGANISATION, "Edited holiday", cleanName, before, summary(holiday), null);
        return view(holiday, locationsById());
    }

    /** The date becomes a normal working day. */
    @Transactional
    public void delete(String code) {
        Holiday holiday = find(code);
        holidays.delete(holiday);
        affected(holiday.getDate(), holiday.getLocationId());
        audit.record(
                AuditCategory.ORGANISATION,
                "Deleted holiday",
                holiday.getName() + " · " + OrgText.date(holiday.getDate()),
                holiday.getType().label(),
                null,
                null);
    }

    /** Every CSV row with its result, as the import would treat it. */
    @Transactional(readOnly = true)
    public ImportPreview preview(String csv) {
        Map<Long, Location> byId = locationsById();
        List<ImportRow> rows = parse(csv).stream().map(r -> importRow(r, byId)).toList();
        return new ImportPreview(
                rows,
                rows.stream().filter(r -> r.error() == null && !r.duplicate()).count(),
                rows.stream().filter(ImportRow::duplicate).count(),
                rows.stream().filter(r -> r.error() != null).count());
    }

    /** Imports the ready rows; rows with errors and existing holidays are skipped. */
    @Transactional
    public ImportResult importCsv(String csv) {
        List<HolidayCsv.Row> rows = parse(csv);
        List<HolidayCsv.Row> ready = rows.stream().filter(HolidayCsv.Row::ready).toList();
        if (ready.isEmpty()) {
            throw new Errors.BusinessRule("NOTHING_TO_IMPORT", "No row is ready to import.");
        }
        List<String> codes = new ArrayList<>();
        for (HolidayCsv.Row row : ready) {
            Holiday holiday = new Holiday(
                    ids.next(CodeType.HOLIDAY, String.valueOf(row.date().getYear())),
                    row.date(),
                    row.name(),
                    row.type(),
                    row.locationId(),
                    "CSV_IMPORT",
                    currentUser.employeeId());
            saveChecked(holiday);
            affected(row.date(), row.locationId());
            codes.add(holiday.getCode());
        }
        String names =
                ready.stream().map(r -> r.name() + " " + OrgText.date(r.date())).collect(Collectors.joining(", "));
        audit.record(
                AuditCategory.ORGANISATION,
                "Imported holidays",
                OrgText.plural(ready.size(), "holiday"),
                null,
                names.length() > AUDIT_LIST_LENGTH ? names.substring(0, AUDIT_LIST_LENGTH) : names,
                null);
        return new ImportResult(ready.size(), rows.size() - ready.size(), codes);
    }

    /** A holiday of the last 21 days changes attendance: those days are recomputed (the prototype's window). */
    private void affected(LocalDate date, Long locationId) {
        LocalDate today = time.today();
        if (!date.isAfter(today) && !date.isBefore(today.minusDays(RECOMPUTE_DAYS))) {
            events.publishEvent(AttendanceAffected.location(locationId, date, date));
        }
    }

    private List<HolidayCsv.Row> parse(String csv) {
        List<HolidayCsv.LocationOption> options = locations.findAll().stream()
                .filter(Location::isActive)
                .map(l -> new HolidayCsv.LocationOption(l.getId(), l.getCode(), l.getName()))
                .toList();
        Set<String> existing = holidays.findAll().stream()
                .map(h -> HolidayCsv.key(h.getDate(), h.getLocationId()))
                .collect(Collectors.toSet());
        return HolidayCsv.parse(csv == null ? "" : csv, options, existing);
    }

    /** The editor's checks; returns the chosen location (null = all locations). */
    private Location check(Holiday self, LocalDate date, String name, String locationCode) {
        Form form = new Form();
        form.check(date == null, "date", "Choose a date.");
        if (form.check(name == null, "name", "Enter a name.")) {
            form.check(name.length() < 2 || name.length() > 100, "name", "Use 2–100 characters.");
        }
        Location location = null;
        String code = Form.clean(locationCode);
        if (code != null) {
            location = locations.findByCode(code).orElse(null);
            boolean unchanged =
                    self != null && location != null && location.getId().equals(self.getLocationId());
            form.check(
                    location == null || (!location.isActive() && !unchanged),
                    "locationCode",
                    "Choose an active location.");
        }
        if (date != null && !form.has("locationCode")) {
            Long locationId = location == null ? null : location.getId();
            form.check(
                    holidays.findAll().stream()
                            .anyMatch(h -> !h.equals(self)
                                    && h.getDate().equals(date)
                                    && Objects.equals(h.getLocationId(), locationId)),
                    "date",
                    DUPLICATE);
        }
        form.throwIfInvalid();
        return location;
    }

    private Holiday find(String code) {
        return holidays.findByCode(code).orElseThrow(() -> new Errors.NotFound("Holiday not found."));
    }

    /** The unique index on (date, location) is the last line of defence against a concurrent duplicate. */
    private void saveChecked(Holiday holiday) {
        try {
            holidays.saveAndFlush(holiday);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("uq_holiday_date_location")) {
                throw new Errors.Conflict("HOLIDAY_EXISTS", DUPLICATE);
            }
            throw e;
        }
    }

    private Map<Long, Location> locationsById() {
        return locations.findAll().stream().collect(Collectors.toMap(Location::getId, Function.identity()));
    }

    private static String summary(Holiday h) {
        return h.getName() + " · " + OrgText.date(h.getDate()) + " · "
                + h.getType().label();
    }

    private static HolidayView view(Holiday h, Map<Long, Location> byId) {
        Location location = h.getLocationId() == null ? null : byId.get(h.getLocationId());
        return new HolidayView(
                h.getCode(),
                h.getDate(),
                h.getDate().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                h.getName(),
                h.getType().name(),
                location == null ? null : new LocationRef(location.getCode(), location.getName()),
                h.getSource(),
                h.isSample(),
                h.getVersion());
    }

    private static ImportRow importRow(HolidayCsv.Row r, Map<Long, Location> byId) {
        Location location = r.locationId() == null ? null : byId.get(r.locationId());
        String result = r.error() != null ? r.error() : r.duplicate() ? "Skipped — already exists" : "Ready";
        return new ImportRow(
                r.line(),
                r.date(),
                r.name(),
                r.type().name(),
                location == null ? null : new LocationRef(location.getCode(), location.getName()),
                r.error(),
                r.duplicate(),
                result);
    }
}
