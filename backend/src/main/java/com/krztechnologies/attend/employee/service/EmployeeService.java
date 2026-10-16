package com.krztechnologies.attend.employee.service;

import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.employee.domain.Employment;
import com.krztechnologies.attend.employee.repository.EmployeeQueries;
import com.krztechnologies.attend.organisation.service.OrganisationDirectory;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Employees: the list with the prototype's filters and the profile (identity, employment, face, consent, days). */
@Service
public class EmployeeService {

    private static final int LAST_DAYS = 14;
    private static final int ACTIVITY_LIMIT = 40;

    private final EmployeeQueries queries;
    private final OrganisationDirectory organisation;
    private final CurrentUser currentUser;
    private final TimeService time;

    public EmployeeService(
            EmployeeQueries queries, OrganisationDirectory organisation, CurrentUser currentUser, TimeService time) {
        this.queries = queries;
        this.organisation = organisation;
        this.currentUser = currentUser;
        this.time = time;
    }

    /** Sort keys of the list: name (default), Employee ID, department, designation, location, type, shift, … */
    static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("name", "name"),
            Map.entry("employeeId", "employeeId"),
            Map.entry("department", "department"),
            Map.entry("designation", "designation"),
            Map.entry("location", "location"),
            Map.entry("type", "type"),
            Map.entry("shift", "shift"),
            Map.entry("face", "face"),
            Map.entry("consent", "consent"),
            Map.entry("tracking", "tracking"),
            Map.entry("joinDate", "joinDate"));

    /**
     * The list filters (all optional; null or empty lists = any).
     *
     * @param quick {@code missing-id}, {@code needs-reenrol}, {@code not-enrolled} or {@code missing-employment}
     * @param faces face statuses
     * @param trackings {@code TRACKED} and reasons not tracked
     * @param shifts the shifts worked today
     * @param employeeIds {@code has} and/or {@code missing}
     * @param accounts {@code enabled} and/or {@code disabled}
     * @param q name, Employee ID, designation or department (also the work e-mail with people.view.sensitive)
     */
    public record ListFilter(
            String quick,
            List<String> departments,
            List<String> locations,
            List<String> faces,
            List<String> trackings,
            List<String> employmentTypes,
            List<String> shifts,
            List<String> employeeIds,
            List<String> accounts,
            String q) {}

    /** @param sort a key of {@link #SORTS} with {@code ,asc} or {@code ,desc} (default name A–Z) */
    @Transactional(readOnly = true)
    public PageResponse<EmployeeViews.ListItem> list(ListFilter f, Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(page, size, sort, SORTS, Sort.by("name"));
        List<EmployeeViews.ListItem> items =
                items(f, pageable.getSort().iterator().next());
        int from = (int) Math.min(pageable.getOffset(), items.size());
        int to = Math.min(from + pageable.getPageSize(), items.size());
        return new PageResponse<>(
                items.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(), items.size());
    }

    /** Every employee of the filter in the list's order (CSV). */
    @Transactional(readOnly = true)
    public List<EmployeeViews.ListItem> export(ListFilter f, String sort) {
        return items(
                f,
                PageRequestSpec.toPageable(0, 1, sort, SORTS, Sort.by("name"))
                        .getSort()
                        .iterator()
                        .next());
    }

    /** The filter options with their counts over every employee. */
    @Transactional(readOnly = true)
    public EmployeeViews.Facets facets() {
        List<EmployeeViews.ListItem> all = items(
                new ListFilter(null, null, null, null, null, null, null, null, null, null), Sort.Order.by("name"));
        return new EmployeeViews.Facets(
                options(
                        all,
                        e -> e.department() == null ? null : e.department().code(),
                        e -> e.department().name()),
                options(
                        all,
                        e -> e.location() == null ? null : e.location().code(),
                        e -> e.location().name()),
                options(all, EmployeeViews.ListItem::employmentType, EmployeeViews.ListItem::employmentType),
                options(all, EmployeeViews.ListItem::faceStatus, EmployeeViews.ListItem::faceStatus),
                options(all, EmployeeService::trackingKey, EmployeeService::trackingKey),
                options(
                        all,
                        e -> e.shiftToday() == null ? null : e.shiftToday().code(),
                        e -> e.shiftToday().name()),
                options(
                        all,
                        e -> e.employeeId() == null ? "missing" : "has",
                        e -> e.employeeId() == null ? "missing" : "has"),
                options(
                        all,
                        e -> e.accountEnabled() ? "enabled" : "disabled",
                        e -> e.accountEnabled() ? "enabled" : "disabled"));
    }

    private List<EmployeeViews.ListItem> items(ListFilter f, Sort.Order order) {
        boolean sensitive = currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE);
        var filter = new EmployeeQueries.Filter(
                f.quick(),
                f.departments(),
                f.locations(),
                f.faces(),
                f.trackings(),
                f.employmentTypes(),
                f.employeeIds(),
                f.accounts(),
                f.q(),
                sensitive);
        ShiftCalendar calendar = organisation.calendar();
        LocalDate today = time.today();
        return queries.list(filter).stream()
                .map(r -> new EmployeeViews.ListItem(
                        r.id(),
                        r.displayName(),
                        sensitive ? email(r) : null,
                        r.hrmsEmployeeId(),
                        r.designation(),
                        ref(r.departmentCode(), r.departmentName()),
                        ref(r.locationCode(), r.locationName()),
                        r.employmentType(),
                        r.faceStatus(),
                        r.consentStatus(),
                        trackingMode(r),
                        trackingReason(r),
                        trackingLabel(r),
                        r.accountEnabled(),
                        r.joinDate(),
                        shiftToday(calendar, r, today),
                        employmentMissing(r)))
                .filter(e -> f.shifts() == null
                        || f.shifts().isEmpty()
                        || (e.shiftToday() != null
                                && f.shifts().contains(e.shiftToday().code())))
                .sorted(comparator(order))
                .toList();
    }

    private static Comparator<EmployeeViews.ListItem> comparator(Sort.Order order) {
        Comparator<String> text = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<EmployeeViews.ListItem> byName =
                Comparator.comparing(EmployeeViews.ListItem::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<EmployeeViews.ListItem> by = switch (order.getProperty()) {
            case "employeeId" -> Comparator.comparing(EmployeeViews.ListItem::employeeId, text);
            case "department" ->
                Comparator.comparing(
                        e -> e.department() == null ? null : e.department().name(), text);
            case "designation" -> Comparator.comparing(EmployeeViews.ListItem::designation, text);
            case "location" ->
                Comparator.comparing(
                        e -> e.location() == null ? null : e.location().name(), text);
            case "type" -> Comparator.comparing(EmployeeViews.ListItem::employmentType, text);
            case "shift" ->
                Comparator.comparing(
                        e -> e.shiftToday() == null ? null : e.shiftToday().name(), text);
            case "face" -> Comparator.comparing(EmployeeViews.ListItem::faceStatus, text);
            case "consent" -> Comparator.comparing(EmployeeViews.ListItem::consentStatus, text);
            case "tracking" -> Comparator.comparing(e -> "TRACKED".equals(e.trackingMode()) ? 0 : 1);
            case "joinDate" ->
                Comparator.comparing(EmployeeViews.ListItem::joinDate, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> byName;
        };
        by = order.isAscending() ? by : by.reversed();
        return by.thenComparing(byName);
    }

    /** {@code TRACKED}, or the reason not tracked (the Tracking filter's values). */
    private static String trackingKey(EmployeeViews.ListItem e) {
        return "TRACKED".equals(e.trackingMode()) ? "TRACKED" : e.trackingReason();
    }

    private static List<EmployeeViews.Option> options(
            List<EmployeeViews.ListItem> items,
            Function<EmployeeViews.ListItem, String> value,
            Function<EmployeeViews.ListItem, String> label) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EmployeeViews.ListItem e : items) {
            String v = value.apply(e);
            if (v != null) {
                labels.putIfAbsent(v, label.apply(e));
                counts.merge(v, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(c -> new EmployeeViews.Option(c.getKey(), labels.get(c.getKey()), c.getValue()))
                .sorted(Comparator.comparing(EmployeeViews.Option::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeeViews.Profile profile(UUID id) {
        EmployeeQueries.Row r = queries.one(id).orElseThrow(() -> new Errors.NotFound("Employee not found."));
        boolean sensitive = currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE);
        LocalDate today = time.today();
        var identity = new EmployeeViews.Identity(
                r.displayName(),
                sensitive || id.equals(currentUser.employeeId()) ? email(r) : null,
                r.objectId(),
                r.tenantId(),
                r.accountEnabled(),
                r.disabledReason(),
                r.leftOn(),
                r.lastSignInAt(),
                r.syncedAt(),
                r.hasAccess());
        var manager = r.managerId() == null
                ? null
                : new EmployeeViews.PersonRef(
                        r.managerId(), queries.displayName(r.managerId()).orElse("—"));
        var employment = new EmployeeViews.Employment(
                r.hrmsEmployeeId(),
                ref(r.departmentCode(), r.departmentName()),
                r.designation(),
                r.employmentType(),
                ref(r.locationCode(), r.locationName()),
                manager,
                r.joinDate(),
                r.endDate(),
                trackingMode(r),
                trackingReason(r),
                trackingLabel(r),
                employmentMissing(r),
                r.employmentVersion());
        var templates = queries.templates(id).stream()
                .map(t -> new EmployeeViews.Template(
                        t.id(),
                        t.pose(),
                        t.quality(),
                        t.source(),
                        t.createdAt(),
                        sensitive && t.hasThumbnail() ? FaceProfileService.thumbnailUrl(id, t.id()) : null))
                .toList();
        var face = new EmployeeViews.Face(
                r.faceStatus(), r.faceReason(), r.scheduledErasureOn(), r.enrolledAt(), templates);
        var consent = queries.consent(id)
                .map(c -> sensitive
                        ? new EmployeeViews.Consent(
                                c.status(),
                                c.method(),
                                c.noticeVersion(),
                                c.recordedBy(),
                                c.recordedAt(),
                                c.givenOn(),
                                c.withdrawnOn(),
                                c.documentMime() != null ? "/api/v1/employees/" + id + "/consent/document" : null,
                                c.documentMime() != null
                                        ? FaceProfileService.consentFileName(r.hrmsEmployeeId(), c.documentMime())
                                        : null)
                        : new EmployeeViews.Consent(c.status(), null, null, null, null, null, null, null, null))
                .orElse(new EmployeeViews.Consent("NONE", null, null, null, null, null, null, null, null));
        List<EmployeeViews.Day> days = currentUser.has(Permission.ATTENDANCE_VIEW_ALL)
                ? queries.days(id, today.minusDays(LAST_DAYS - 1L), today).stream()
                        .map(d -> new EmployeeViews.Day(
                                d.day(),
                                d.shiftCode(),
                                d.shiftName(),
                                d.status(),
                                d.statusCode(),
                                d.checkIn(),
                                d.checkOut(),
                                d.workedMinutes(),
                                d.late(),
                                d.finalised(),
                                d.checkOutFinal(),
                                d.missingCheckOut()))
                        .toList()
                : null;
        var activity = queries.history(id, r.displayName(), ACTIVITY_LIMIT).stream()
                .map(h -> new EmployeeViews.Activity(h.at(), h.action(), h.by(), h.before(), h.after(), h.detail()))
                .toList();
        return new EmployeeViews.Profile(
                id, identity, employment, shiftToday(organisation.calendar(), r, today), face, consent, days, activity);
    }

    /** The shift the employee would work today in another department (the employment editor's "Shift"). */
    @Transactional(readOnly = true)
    public EmployeeViews.ShiftToday shiftWithDepartment(UUID id, String departmentCode) {
        EmployeeQueries.Row r = queries.one(id).orElseThrow(() -> new Errors.NotFound("Employee not found."));
        Long departmentId = departmentCode == null
                ? r.departmentId()
                : organisation
                        .department(departmentCode)
                        .map(d -> d.id())
                        .orElseThrow(() -> new Errors.NotFound("Department not found."));
        ShiftCalendar.Effective effective = organisation
                .calendar()
                .effective(new ShiftCalendar.Person(r.id(), departmentId, r.locationId()), time.today());
        var terms = effective.terms();
        return new EmployeeViews.ShiftToday(
                terms.shiftCode(),
                terms.shiftName(),
                terms.colour(),
                terms.timingText(),
                organisation.sourceText(effective));
    }

    private EmployeeViews.ShiftToday shiftToday(ShiftCalendar calendar, EmployeeQueries.Row r, LocalDate day) {
        ShiftCalendar.Effective effective =
                calendar.effective(new ShiftCalendar.Person(r.id(), r.departmentId(), r.locationId()), day);
        var terms = effective.terms();
        return new EmployeeViews.ShiftToday(
                terms.shiftCode(),
                terms.shiftName(),
                terms.colour(),
                terms.timingText(),
                organisation.sourceText(effective));
    }

    private static String email(EmployeeQueries.Row r) {
        return r.mail() != null ? r.mail() : r.upn();
    }

    private static EmployeeViews.Ref ref(String code, String name) {
        return code == null ? null : new EmployeeViews.Ref(code, name);
    }

    private static String trackingMode(EmployeeQueries.Row r) {
        return r.trackingMode() == null ? Employment.TrackingMode.NOT_TRACKED.name() : r.trackingMode();
    }

    private static String trackingReason(EmployeeQueries.Row r) {
        return r.trackingMode() == null ? Employment.TrackingReason.NO_FACE.name() : r.trackingReason();
    }

    private static String trackingLabel(EmployeeQueries.Row r) {
        boolean tracked = "TRACKED".equals(trackingMode(r));
        return EmployeeDirectory.trackingLabel(
                r.accountEnabled(),
                tracked,
                tracked
                        ? ""
                        : Employment.TrackingReason.valueOf(trackingReason(r)).label());
    }

    static boolean employmentMissing(EmployeeQueries.Row r) {
        return !r.hasEmployment() || r.designation() == null || r.locationId() == null || r.joinDate() == null;
    }
}
