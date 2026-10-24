package com.krztechnologies.attend.crossing.service;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.AttendPrincipal;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.crossing.repository.CrossingQueries;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import com.krztechnologies.attend.enrolment.service.TemplateService;
import com.krztechnologies.attend.recognition.service.ActiveThresholds;
import com.krztechnologies.attend.recognition.service.ConfidenceBands;
import com.krztechnologies.attend.recognition.service.RecognitionService;
import com.krztechnologies.attend.rules.service.ShiftTerms;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Events page: crossings with filters (default yesterday and today), a crossing's detail, the CSV rows, and a face
 * template from an identified crossing's crop (D-118).
 */
@Service
public class CrossingService {

    /** The longest date range one list or export may span. */
    static final int MAX_RANGE_DAYS = 366;

    /** The largest export (narrow the range beyond that). */
    static final int MAX_EXPORT_ROWS = 50_000;

    private static final int HISTORY_LIMIT = 8;

    private final CrossingQueries queries;
    private final CrossingDecisions decisions;
    private final EmployeeDirectory employees;
    private final RecognitionService recognition;
    private final TemplateService templates;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final TimeService time;
    private final JsonMapper json;

    public CrossingService(
            CrossingQueries queries,
            CrossingDecisions decisions,
            EmployeeDirectory employees,
            RecognitionService recognition,
            TemplateService templates,
            AuditService audit,
            CurrentUser currentUser,
            TimeService time,
            JsonMapper json) {
        this.queries = queries;
        this.decisions = decisions;
        this.employees = employees;
        this.recognition = recognition;
        this.templates = templates;
        this.audit = audit;
        this.currentUser = currentUser;
        this.time = time;
        this.json = json;
    }

    /**
     * Adds the identified crossing's face crop as the employee's face template (D-118): consent on file, a clear and
     * nearly frontal face, not already a template, not looking like another employee, and never one's own profile.
     */
    @Transactional
    public CrossingViews.EventDetail addTemplate(String code) {
        CrossingQueries.Row row = queries.one(code).orElseThrow(() -> new Errors.NotFound("Crossing not found."));
        if (row.employeeId() == null) {
            throw new Errors.BusinessRule(
                    "TEMPLATE_NOT_ELIGIBLE", "Only a crossing with a recognised employee can give a face template.");
        }
        AttendPrincipal me = currentUser.require();
        AuditActor actor = AuditActor.employee(me.employeeId(), me.displayName());
        templates.addFromCrossing(
                row.employeeId(), row.employeeName(), row.id(), row.code(), crop(row, queries.extra(row.id())), actor);
        audit.record(
                actor,
                AuditCategory.ENROLMENT,
                "Added a face template from a crossing",
                row.employeeName(),
                null,
                row.code(),
                null);
        return detail(code);
    }

    private static TemplateService.Crop crop(CrossingQueries.Row row, CrossingQueries.Extra extra) {
        return new TemplateService.Crop(
                extra.faceCrop(), extra.quality(), "REID".equals(row.method()), row.cropsPurged());
    }

    /**
     * The list filters (all optional).
     *
     * @param from first IST day (default yesterday)
     * @param to last IST day (default today)
     * @param methods {@code FACE}, {@code REID}, {@code REVIEW} and {@code NONE} (not identified)
     * @param q event ID, name, Employee ID, unknown label or rule reason
     */
    public record ListFilter(
            LocalDate from,
            LocalDate to,
            List<String> cameras,
            List<String> directions,
            List<String> methods,
            List<String> decisions,
            List<String> effects,
            List<UUID> employeeIds,
            String q) {}

    /** @param sort {@code time} (default, newest first), {@code code}, {@code direction}, {@code person}, … */
    @Transactional(readOnly = true)
    public PageResponse<CrossingViews.EventRow> list(ListFilter filter, Integer page, Integer size, String sort) {
        Pageable pageable = PageRequestSpec.toPageable(
                page,
                size,
                sort,
                CrossingQueries.SORTS,
                Sort.by(Sort.Direction.DESC, CrossingQueries.SORTS.get("time")));
        Sort.Order order = pageable.getSort().iterator().next();
        CrossingQueries.Filter f = filter(filter);
        Mapper mapper = mapper();
        List<CrossingViews.EventRow> items = queries
                .list(f, order.getProperty(), order.isAscending(), pageable.getOffset(), pageable.getPageSize())
                .stream()
                .map(mapper::row)
                .toList();
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), queries.count(f));
    }

    /**
     * The newest crossings of yesterday and today (Live View's "Last 10 crossings", the Dashboard feed).
     *
     * @param camera one camera's crossings, or null for all
     */
    @Transactional(readOnly = true)
    public List<CrossingViews.EventRow> recent(String camera, int limit) {
        CrossingQueries.Filter f = filter(new ListFilter(
                null, null, camera == null ? List.of() : List.of(camera), null, null, null, null, null, null));
        Mapper mapper = mapper();
        return queries.list(f, CrossingQueries.SORTS.get("time"), false, 0, limit).stream()
                .map(mapper::row)
                .toList();
    }

    /** Identified crossings per IST day, hour and direction from {@code first} to {@code last} (the Dashboard chart). */
    @Transactional(readOnly = true)
    public List<CrossingViews.HourCount> hourly(LocalDate first, LocalDate last) {
        return queries
                .hourly(
                        time.startOfDay(first),
                        time.startOfDay(last.plusDays(1)),
                        time.zone().getId())
                .stream()
                .map(h -> new CrossingViews.HourCount(h.day(), h.hour(), h.direction(), h.count()))
                .toList();
    }

    /** Every crossing of the filter in the list's order (CSV). */
    @Transactional(readOnly = true)
    public List<CrossingViews.EventRow> export(ListFilter filter, String sort) {
        Sort.Order order = PageRequestSpec.toPageable(
                        0,
                        1,
                        sort,
                        CrossingQueries.SORTS,
                        Sort.by(Sort.Direction.DESC, CrossingQueries.SORTS.get("time")))
                .getSort()
                .iterator()
                .next();
        CrossingQueries.Filter f = filter(filter);
        if (queries.count(f) > MAX_EXPORT_ROWS) {
            throw new Errors.BusinessRule(
                    "EXPORT_TOO_LARGE", "More than 50,000 crossings match. Narrow the date range or the filters.");
        }
        Mapper mapper = mapper();
        return queries.list(f, order.getProperty(), order.isAscending(), 0, MAX_EXPORT_ROWS).stream()
                .map(mapper::row)
                .toList();
    }

    /** The filter options of the chosen days (default yesterday and today) with their counts. */
    @Transactional(readOnly = true)
    public CrossingViews.Facets facets(LocalDate from, LocalDate to) {
        CrossingQueries.Filter range = filter(new ListFilter(from, to, null, null, null, null, null, null, null));
        CrossingQueries.Facets f = queries.facets(range.from(), range.to());
        return new CrossingViews.Facets(
                options(f.directions()),
                options(f.methods()),
                options(f.decisions()),
                options(f.effects()),
                options(f.cameras()),
                options(f.employees()));
    }

    private static List<CrossingViews.Option> options(List<CrossingQueries.ValueCount> counts) {
        return counts.stream()
                .map(c -> new CrossingViews.Option(c.value(), c.label(), c.count()))
                .toList();
    }

    /**
     * The stored crop files of a crossing, for the media endpoint: paths are only ever looked up here, never taken from
     * the client.
     *
     * @param face the face crop under {@code crops/}, null when none was captured (likewise the others)
     * @param purged the retention job (or an unknown face's expiry) deleted the files
     */
    public record StoredCrops(String face, String faceBlur, String body, String bodyBlur, boolean purged) {}

    @Transactional(readOnly = true)
    public Optional<StoredCrops> crops(String code) {
        return queries.crops(code)
                .map(c -> new StoredCrops(c.face(), c.faceBlur(), c.body(), c.bodyBlur(), c.purged()));
    }

    @Transactional(readOnly = true)
    public CrossingViews.EventDetail detail(String code) {
        CrossingQueries.Row row = queries.one(code).orElseThrow(() -> new Errors.NotFound("Crossing not found."));
        CrossingQueries.Extra extra = queries.extra(row.id());
        ActiveThresholds values = recognition.activeValues();
        ConfidenceBands bands = new ConfidenceBands(values.accept());
        boolean showScores = currentUser.has(Permission.SETTINGS_RECOGNITION);
        CrossingViews.AttendanceRef attendance = null;
        if (row.employeeId() != null && extra.shiftDay() != null) {
            var profile =
                    employees.attendanceProfiles(List.of(row.employeeId())).get(row.employeeId());
            String tracking = profile == null ? null : profile.trackingLabel();
            attendance = queries.day(row.employeeId(), extra.shiftDay())
                    .map(d -> new CrossingViews.AttendanceRef(
                            d.shiftDay(),
                            d.shiftCode(),
                            d.shiftName(),
                            ShiftTerms.timingText(
                                    time.zoned(d.shiftStart()).toLocalTime(),
                                    time.zoned(d.shiftEnd()).toLocalTime()),
                            d.status(),
                            d.statusCode(),
                            d.finalised(),
                            tracking))
                    .orElse(null);
        }
        Instant matchedIn = "REID".equals(row.method()) && row.employeeId() != null
                ? decisions
                        .lastIn(
                                row.employeeId(),
                                time.startOfDay(time.zoned(row.occurredAt()).toLocalDate()),
                                row.occurredAt())
                        .map(CrossingDecisions.InCrossing::occurredAt)
                        .orElse(null)
                : null;
        CrossingViews.TemplateOffer template = null;
        if (row.employeeId() != null && row.hasFaceCrop() && currentUser.has(Permission.PEOPLE_ENROL)) {
            var offer = templates.offer(
                    row.employeeId(),
                    row.employeeName(),
                    row.id(),
                    crop(row, extra),
                    currentUser.require().employeeId());
            template = new CrossingViews.TemplateOffer(offer.ok(), offer.why());
        }
        return new CrossingViews.EventDetail(
                mapper().row(row),
                extra.entryPoint(),
                extra.department(),
                FaceQuality.of(extra.quality() == null ? null : json.readTree(extra.quality()), values),
                match(row.score(), bands, showScores),
                match(row.reidScore(), bands, showScores),
                matchedIn,
                extra.trackId(),
                extra.reviewCode() == null
                        ? null
                        : new CrossingViews.ReviewRef(extra.reviewCode(), extra.reviewStatus()),
                extra.resolvedBy(),
                extra.resolvedAt(),
                extra.flipReason() == null ? null : new CrossingViews.Correction(extra.flipBy(), extra.flipReason()),
                extra.voidReason() == null ? null : new CrossingViews.Correction(extra.voidBy(), extra.voidReason()),
                attendance,
                queries.punches(row.code()).stream()
                        .map(p -> new CrossingViews.PunchRow(
                                p.punchId(), p.kind(), p.type(), p.status(), p.lastAttemptAt()))
                        .toList(),
                queries.history(row.code(), HISTORY_LIMIT).stream()
                        .map(h -> new CrossingViews.ChangeRow(h.at(), h.action(), h.actor(), h.after()))
                        .toList(),
                template);
    }

    private static CrossingViews.Match match(BigDecimal score, ConfidenceBands bands, boolean showScores) {
        return score == null ? null : new CrossingViews.Match(bands.band(score), showScores ? score : null);
    }

    private CrossingQueries.Filter filter(ListFilter f) {
        LocalDate to = f.to() == null ? time.today() : f.to();
        LocalDate from = f.from() == null ? to.minusDays(1) : f.from();
        if (to.isBefore(from)) {
            throw new Errors.BadRequest("INVALID_RANGE", "The end date can’t be before the start date.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new Errors.BadRequest("INVALID_RANGE", "Choose at most " + MAX_RANGE_DAYS + " days.");
        }
        return new CrossingQueries.Filter(
                time.startOfDay(from),
                time.startOfDay(to.plusDays(1)),
                f.cameras(),
                f.directions(),
                f.methods(),
                f.decisions(),
                f.effects(),
                f.employeeIds(),
                f.q());
    }

    private Mapper mapper() {
        return new Mapper(recognition.confidenceBands(), currentUser.has(Permission.SETTINGS_RECOGNITION));
    }

    /** Row mapping with the bands loaded once per request. */
    private record Mapper(ConfidenceBands bands, boolean showScores) {

        CrossingViews.EventRow row(CrossingQueries.Row r) {
            var score = "REID".equals(r.method()) ? r.reidScore() : r.score();
            return new CrossingViews.EventRow(
                    r.code(),
                    r.occurredAt(),
                    new CrossingViews.CameraRef(r.cameraCode(), r.cameraName()),
                    r.direction(),
                    r.originalDirection(),
                    r.uTurn(),
                    r.employeeId() == null
                            ? null
                            : new CrossingViews.PersonRef(r.employeeId(), r.employeeName(), r.hrmsEmployeeId()),
                    r.unknownLabel(),
                    r.method(),
                    r.employeeId() == null ? null : bands.band(score),
                    showScores && r.employeeId() != null ? score : null,
                    r.decision(),
                    r.liveness(),
                    r.effect(),
                    r.effectReason(),
                    r.hasFaceCrop(),
                    r.hasBodyCrop(),
                    r.cropsPurged(),
                    r.directionUnsure());
        }
    }
}
