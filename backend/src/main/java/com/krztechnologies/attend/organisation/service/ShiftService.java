package com.krztechnologies.attend.organisation.service;

import com.krztechnologies.attend.common.attendance.AttendanceAffected;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.organisation.domain.OrgText;
import com.krztechnologies.attend.organisation.domain.RecordStatus;
import com.krztechnologies.attend.organisation.domain.Shift;
import com.krztechnologies.attend.organisation.domain.ShiftRules;
import com.krztechnologies.attend.organisation.domain.ShiftVersion;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries;
import com.krztechnologies.attend.organisation.repository.OrganisationQueries.PersonRow;
import com.krztechnologies.attend.organisation.repository.ShiftRepository;
import com.krztechnologies.attend.organisation.repository.ShiftVersionRepository;
import com.krztechnologies.attend.organisation.service.OrganisationViews.RecalculationImpact;
import com.krztechnologies.attend.organisation.service.OrganisationViews.RulesView;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ShiftCard;
import com.krztechnologies.attend.organisation.service.OrganisationViews.ShiftDetail;
import com.krztechnologies.attend.organisation.service.OrganisationViews.VersionView;
import com.krztechnologies.attend.rules.service.ShiftCalendar;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organisation → Shifts (R10): shifts with effective-dated versions (past days keep their version; a version from
 * today or earlier needs the recalculation confirmation), name and colour from the palette, deactivation (never the
 * default, never while allocated), and exactly one organisation default. Every change is audited (Organisation).
 */
@Service
public class ShiftService {

    private static final Pattern SHIFT_CODE = Pattern.compile("^[A-Z0-9-]{2,8}$");
    /** The wizard's date field allows at most three weeks back. */
    private static final int MAX_DAYS_BACK = 21;

    private final ShiftRepository shifts;
    private final ShiftVersionRepository versions;
    private final OrganisationQueries queries;
    private final OrganisationDirectory directory;
    private final AllocationService allocations;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final ApplicationEventPublisher events;

    public ShiftService(
            ShiftRepository shifts,
            ShiftVersionRepository versions,
            OrganisationQueries queries,
            OrganisationDirectory directory,
            AllocationService allocations,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            ApplicationEventPublisher events) {
        this.shifts = shifts;
        this.versions = versions;
        this.queries = queries;
        this.directory = directory;
        this.allocations = allocations;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.events = events;
    }

    /** Active shifts first, then by name (the prototype's card order). */
    @Transactional(readOnly = true)
    public PageResponse<ShiftCard> list(RecordStatus status, Integer page, Integer size, String sort) {
        Comparator<ShiftCard> activeFirst = Comparator.comparing(
                        (ShiftCard c) -> !c.status().equals("ACTIVE"))
                .thenComparing(ShiftCard::name, String.CASE_INSENSITIVE_ORDER);
        return Pages.of(
                all(status),
                page,
                size,
                sort,
                Map.of(
                        "default", activeFirst,
                        "name", Pages.text(ShiftCard::name),
                        "code", Pages.text(ShiftCard::code),
                        "employees", Comparator.comparingLong(ShiftCard::employeesToday)),
                Sort.by("default"));
    }

    /** Every shift with the given status (list and export). */
    @Transactional(readOnly = true)
    public List<ShiftCard> all(RecordStatus status) {
        Context context = context();
        return shifts.findAll().stream()
                .filter(s -> status == null || s.getStatus() == status)
                .map(context::card)
                .toList();
    }

    @Transactional(readOnly = true)
    public ShiftDetail get(String code) {
        return detail(find(code), context());
    }

    /**
     * @param effectiveFrom first day of the first version; null = today
     * @param colour null = the first palette colour no shift uses yet
     */
    @Transactional
    public ShiftDetail create(String code, String name, String colour, LocalDate effectiveFrom, ShiftRules rules) {
        String cleanCode = Form.clean(code);
        String cleanName = Form.clean(name);
        Form form = new Form();
        if (form.check(cleanCode == null, "code", "Use 2–8 capital letters, digits or dashes.")) {
            if (form.check(
                    !SHIFT_CODE.matcher(cleanCode).matches(), "code", "Use 2–8 capital letters, digits or dashes.")) {
                form.check(shifts.existsByCode(cleanCode), "code", "This code is already used.");
            }
        }
        checkName(form, cleanName, null);
        String chosenColour = colour == null ? freeColour() : colour.trim().toLowerCase(Locale.ROOT);
        checkColour(form, chosenColour);
        form.addAll(rules.problems());
        form.throwIfInvalid();
        AttendPrincipal me = currentUser.require();
        Shift shift = shifts.save(new Shift(cleanCode, cleanName, chosenColour, me.employeeId()));
        ShiftVersion version = new ShiftVersion(shift.getId(), effectiveFrom == null ? time.today() : effectiveFrom);
        version.apply(rules, "Created", me.employeeId(), me.displayName(), time.now());
        versions.save(version);
        audit.record(AuditCategory.ORGANISATION, "Created shift", shift.getName(), null, rules.timingText(), null);
        return detail(shift, context());
    }

    /** What a version from {@code effectiveFrom} (null = tomorrow) recalculates. */
    @Transactional(readOnly = true)
    public RecalculationImpact preview(String code, LocalDate effectiveFrom) {
        Shift shift = find(code);
        LocalDate today = time.today();
        LocalDate from = effectiveFrom == null ? today.plusDays(1) : effectiveFrom;
        Form form = new Form();
        checkNotTooEarly(form, from, today);
        form.throwIfInvalid();
        Context context = context();
        boolean recalculates = !from.isAfter(today);
        int days = recalculates ? (int) ChronoUnit.DAYS.between(from, today) + 1 : 0;
        long employeeDays = 0;
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            employeeDays += context.employeesOn(shift.getCode(), day);
        }
        return new RecalculationImpact(
                from,
                recalculates,
                days,
                context.employeesOn(shift.getCode(), recalculates ? today : from),
                employeeDays);
    }

    /**
     * New rules from {@code effectiveFrom} (null = tomorrow). A day that already has a version gets its values
     * replaced. Today or earlier needs {@code confirmRecalculation}; at most 21 days back.
     */
    @Transactional
    public ShiftDetail addVersion(
            String code, LocalDate effectiveFrom, ShiftRules rules, String note, boolean confirmRecalculation) {
        Shift shift = find(code);
        if (!shift.isActive()) {
            throw new Errors.Conflict(
                    "SHIFT_ARCHIVED", shift.getName() + " is inactive. Reactivate it to change its rules.");
        }
        LocalDate today = time.today();
        LocalDate from = effectiveFrom == null ? today.plusDays(1) : effectiveFrom;
        String cleanNote = Form.clean(note);
        Form form = new Form();
        form.addAll(rules.problems());
        if (form.check(cleanNote == null, "note", "Say why the rules change — it goes in the audit log.")) {
            form.check(cleanNote.length() > 500, "note", "Use at most 500 characters.");
        }
        if (checkNotTooEarly(form, from, today)) {
            form.check(
                    !from.isAfter(today) && !confirmRecalculation,
                    "confirmRecalculation",
                    "Tick the box to confirm recalculating past days, or choose a later date.");
        }
        form.throwIfInvalid();
        Context context = context();
        ShiftRules before = context.versionOn(shift.getId(), from).rules();
        AttendPrincipal me = currentUser.require();
        ShiftVersion version = versions.findByShiftIdAndEffectiveFrom(shift.getId(), from)
                .orElseGet(() -> new ShiftVersion(shift.getId(), from));
        version.apply(rules, cleanNote, me.employeeId(), me.displayName(), time.now());
        versions.save(version);
        audit.record(
                AuditCategory.ORGANISATION,
                "Changed shift rules",
                shift.getName() + " from " + OrgText.date(from),
                rulesSummary(before),
                rulesSummary(rules) + " — " + cleanNote + (from.isAfter(today) ? "" : " · past days recalculated"),
                null);
        if (!from.isAfter(today)) {
            versions.flush();
            events.publishEvent(AttendanceAffected.shift(shift.getCode(), from, today));
        }
        return detail(shift, context());
    }

    /** Name and colour (the code never changes). */
    @Transactional
    public ShiftDetail update(String code, String name, String colour, int version) {
        Shift shift = find(code);
        if (shift.getVersion() != version) {
            throw Form.stale();
        }
        String cleanName = Form.clean(name);
        String chosenColour = colour == null ? shift.getColour() : colour.trim().toLowerCase(Locale.ROOT);
        Form form = new Form();
        checkName(form, cleanName, shift);
        checkColour(form, chosenColour);
        form.throwIfInvalid();
        String before = shift.getName() + " · " + shift.getColour();
        shift.update(cleanName, chosenColour);
        audit.record(
                AuditCategory.ORGANISATION,
                "Edited shift",
                shift.getName(),
                before,
                shift.getName() + " · " + shift.getColour(),
                null);
        return detail(shift, context());
    }

    /** Refused for the organisation default and while the shift has active or scheduled allocations. */
    @Transactional
    public ShiftDetail archive(String code) {
        Shift shift = find(code);
        if (shift.isActive()) {
            Context context = context();
            String blocked = context.archiveBlockedReason(shift);
            if (blocked != null) {
                throw new Errors.Conflict(shift.isDefault() ? "DEFAULT_SHIFT" : "SHIFT_IN_USE", blocked);
            }
            shift.setStatus(RecordStatus.ARCHIVED);
            audit.record(AuditCategory.ORGANISATION, "Deactivated shift", shift.getName(), "Active", "Inactive", null);
        }
        return detail(shift, context());
    }

    @Transactional
    public ShiftDetail restore(String code) {
        Shift shift = find(code);
        if (!shift.isActive()) {
            shift.setStatus(RecordStatus.ACTIVE);
            audit.record(AuditCategory.ORGANISATION, "Reactivated shift", shift.getName(), "Inactive", "Active", null);
        }
        return detail(shift, context());
    }

    /** Exactly one default shift (R1): the previous default stops being it in the same transaction. */
    @Transactional
    public ShiftDetail makeDefault(String code) {
        Shift shift = find(code);
        if (!shift.isActive()) {
            throw new Errors.Conflict(
                    "SHIFT_ARCHIVED", shift.getName() + " is inactive. Reactivate it to make it the default.");
        }
        if (!shift.isDefault()) {
            Shift previous = shifts.findByIsDefaultTrue().orElseThrow();
            previous.setDefault(false);
            shifts.flush();
            shift.setDefault(true);
            shifts.flush();
            audit.record(
                    AuditCategory.ORGANISATION,
                    "Changed default shift",
                    "Organisation",
                    previous.getName(),
                    shift.getName(),
                    null);
            events.publishEvent(AttendanceAffected.shift(shift.getCode(), time.today(), time.today()));
        }
        return detail(shift, context());
    }

    private Shift find(String code) {
        return shifts.findByCode(code).orElseThrow(() -> new Errors.NotFound("Shift not found."));
    }

    private void checkName(Form form, String name, Shift self) {
        if (form.check(name == null, "name", "Enter a name.")) {
            if (form.check(name.length() < 2 || name.length() > 60, "name", "Use 2–60 characters.")) {
                form.check(
                        (self == null || !self.getName().equalsIgnoreCase(name)) && shifts.existsByNameIgnoreCase(name),
                        "name",
                        "Another shift has this name.");
            }
        }
    }

    private static void checkColour(Form form, String colour) {
        form.check(!Shift.PALETTE.contains(colour), "colour", "Choose one of the palette colours.");
    }

    /** Returns whether the date is fine. */
    private static boolean checkNotTooEarly(Form form, LocalDate from, LocalDate today) {
        return form.check(
                from.isBefore(today.minusDays(MAX_DAYS_BACK)),
                "effectiveFrom",
                "Choose a date in the last " + MAX_DAYS_BACK + " days or later.");
    }

    private String freeColour() {
        List<String> used = shifts.findAll().stream().map(Shift::getColour).toList();
        return Shift.PALETTE.stream().filter(c -> !used.contains(c)).findFirst().orElse(Shift.PALETTE.getFirst());
    }

    /** "09:30–18:30 · late 15 · early 15" (the prototype's before/after of a rules change). */
    private static String rulesSummary(ShiftRules rules) {
        return rules.timingText() + " · late " + rules.lateGraceMinutes() + " · early " + rules.earlyGraceMinutes();
    }

    private ShiftDetail detail(Shift shift, Context context) {
        LocalDate today = context.today();
        ShiftVersion current = context.versionOn(shift.getId(), today);
        List<VersionView> history = context.versions(shift.getId()).stream()
                .sorted(Comparator.comparing(ShiftVersion::getEffectiveFrom).reversed())
                .map(v -> new VersionView(
                        rulesView(v),
                        v.getNote(),
                        v.getEditedByLabel(),
                        v.getEditedAt(),
                        v.getEffectiveFrom().isAfter(today)
                                ? "SCHEDULED"
                                : v.getId().equals(current.getId()) ? "CURRENT" : "PAST"))
                .toList();
        return new ShiftDetail(
                context.card(shift),
                history,
                allocations.ofShift(shift.getCode()),
                shift.isActive() ? context.archiveBlockedReason(shift) : null);
    }

    static RulesView rulesView(ShiftVersion version) {
        ShiftRules r = version.rules();
        return new RulesView(
                version.getEffectiveFrom(),
                r.start().toString(),
                r.end().toString(),
                !r.end().isAfter(r.start()),
                r.timingText(),
                r.breakMinutes(),
                r.weeklyOffs(),
                r.weeklyOffText(),
                r.lateGraceMinutes(),
                r.earlyGraceMinutes(),
                r.halfDayBelowHours(),
                r.absentBelowHours(),
                r.windowBeforeHours(),
                r.dayCloseHours());
    }

    private Context context() {
        return new Context(
                time.today(),
                directory.calendar(),
                versions.findAll().stream().collect(Collectors.groupingBy(ShiftVersion::getShiftId)),
                queries.people());
    }

    /** What one request reads once. */
    private record Context(
            LocalDate today,
            ShiftCalendar calendar,
            Map<Long, List<ShiftVersion>> versionsByShift,
            List<PersonRow> people) {

        List<ShiftVersion> versions(Long shiftId) {
            return versionsByShift.getOrDefault(shiftId, List.of()).stream()
                    .sorted(Comparator.comparing(ShiftVersion::getEffectiveFrom))
                    .toList();
        }

        /** The version with the greatest {@code effectiveFrom ≤ day}, else the first one (as the calendar does). */
        ShiftVersion versionOn(Long shiftId, LocalDate day) {
            List<ShiftVersion> list = versions(shiftId);
            ShiftVersion chosen = list.getFirst();
            for (ShiftVersion v : list) {
                if (!v.getEffectiveFrom().isAfter(day)) {
                    chosen = v;
                }
            }
            return chosen;
        }

        /** Enabled employees employed on {@code day} whose effective shift that day is {@code shiftCode}. */
        long employeesOn(String shiftCode, LocalDate day) {
            return people.stream()
                    .filter(p -> p.worksOn(day))
                    .filter(p -> calendar.effective(p.person(), day)
                            .terms()
                            .shiftCode()
                            .equals(shiftCode))
                    .count();
        }

        /** Not cancelled and not ended: in effect today or later. */
        List<ShiftCalendar.Allocation> current(String shiftCode) {
            return calendar.allocations().stream()
                    .filter(a -> a.shiftCode().equals(shiftCode)
                            && (a.until() == null || !a.until().isBefore(today)))
                    .toList();
        }

        String archiveBlockedReason(Shift shift) {
            if (shift.isDefault()) {
                return "This is the organisation default shift.";
            }
            return current(shift.getCode()).isEmpty() ? null : "End its allocations first.";
        }

        ShiftCard card(Shift shift) {
            List<ShiftVersion> list = versions(shift.getId());
            ShiftVersion todays = versionOn(shift.getId(), today);
            ShiftVersion scheduled = list.stream()
                    .filter(v -> v.getEffectiveFrom().isAfter(today))
                    .findFirst()
                    .orElse(null);
            List<ShiftCalendar.Allocation> live = current(shift.getCode());
            return new ShiftCard(
                    shift.getCode(),
                    shift.getName(),
                    shift.getColour(),
                    shift.getStatus().name(),
                    shift.isDefault(),
                    shift.getSource(),
                    rulesView(todays),
                    scheduled == null ? null : rulesView(scheduled),
                    employeesOn(shift.getCode(), today),
                    live.stream()
                            .filter(a -> a.target() == ShiftCalendar.Target.DEPARTMENT)
                            .count(),
                    live.stream()
                            .filter(a -> a.target() == ShiftCalendar.Target.EMPLOYEE)
                            .count(),
                    shift.getCreatedAt(),
                    shift.getVersion());
        }
    }
}
