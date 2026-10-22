package com.krztechnologies.attend.crossing.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.crossing.service.CrossingService;
import com.krztechnologies.attend.crossing.service.CrossingViews;
import com.krztechnologies.attend.crossing.service.CrossingViews.EventRow;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Events: every crossing at the entrance cameras with its punch effect, the crossing drawer and the CSV export. */
@RestController
@RequestMapping("/api/v1/crossings")
@Validated
public class CrossingController {

    private static final String CODE = "^EV-\\d{6,}$";
    private static final String CAMERA = "^CAM-\\d{2,}$";
    private static final String METHODS = "FACE|REID|REVIEW|NONE";
    private static final String DECISIONS =
            "AUTO|PENDING_REVIEW|RESOLVED_IN_REVIEW|NOT_AN_EMPLOYEE|REJECTED|SPOOF_CONFIRMED|EXPIRED_NO_ACTION";
    private static final String EFFECTS = "CHECK_IN|CHECK_OUT|MOVEMENT|IGNORED_DUPLICATE|IGNORED_TURNED_BACK"
            + "|IGNORED_UNKNOWN|PENDING_REVIEW|VOIDED";

    /** The prototype's labels for the CSV. */
    private static final Map<String, String> METHOD_LABELS =
            Map.of("FACE", "Face", "REID", "Re-ID", "REVIEW", "Review");

    private static final Map<String, String> DECISION_LABELS = Map.of(
            "AUTO", "Auto",
            "PENDING_REVIEW", "Pending review",
            "RESOLVED_IN_REVIEW", "Resolved in review",
            "NOT_AN_EMPLOYEE", "Not an employee",
            "REJECTED", "Rejected",
            "SPOOF_CONFIRMED", "Spoof confirmed",
            "EXPIRED_NO_ACTION", "Expired – no action");

    private static final Map<String, String> EFFECT_LABELS = Map.of(
            "CHECK_IN", "Check-in",
            "CHECK_OUT", "Check-out",
            "MOVEMENT", "Movement",
            "IGNORED_DUPLICATE", "Ignored – duplicate",
            "IGNORED_TURNED_BACK", "Ignored – turned back",
            "IGNORED_UNKNOWN", "Ignored – unknown",
            "PENDING_REVIEW", "Pending review",
            "VOIDED", "Voided");

    private final CrossingService crossings;
    private final CsvExportService csv;
    private final TimeService time;

    public CrossingController(CrossingService crossings, CsvExportService csv, TimeService time) {
        this.crossings = crossings;
        this.csv = csv;
        this.time = time;
    }

    @Operation(summary = "Crossings with filters (default yesterday and today), newest first")
    @RequiresPermission(Permission.EVENTS_VIEW)
    @GetMapping
    public PageResponse<EventRow> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 50) List<@Pattern(regexp = CAMERA) String> camera,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "IN|OUT") String> direction,
            @RequestParam(required = false) @Size(max = 4) List<@Pattern(regexp = METHODS) String> method,
            @RequestParam(required = false) @Size(max = 7) List<@Pattern(regexp = DECISIONS) String> decision,
            @RequestParam(required = false) @Size(max = 8) List<@Pattern(regexp = EFFECTS) String> effect,
            @RequestParam(required = false) @Size(max = 200) List<UUID> employeeId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return crossings.list(
                new CrossingService.ListFilter(from, to, camera, direction, method, decision, effect, employeeId, q),
                page,
                size,
                sort);
    }

    @Operation(
            summary = "The Events filters' options with their counts in the chosen days (default yesterday and today)")
    @RequiresPermission(Permission.EVENTS_VIEW)
    @GetMapping("/facets")
    public CrossingViews.Facets facets(
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return crossings.facets(from, to);
    }

    @Operation(summary = "The crossings of the filter as CSV, in the list's order (at most 50,000)")
    @RequiresPermission({Permission.EVENTS_VIEW, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 50) List<@Pattern(regexp = CAMERA) String> camera,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "IN|OUT") String> direction,
            @RequestParam(required = false) @Size(max = 4) List<@Pattern(regexp = METHODS) String> method,
            @RequestParam(required = false) @Size(max = 7) List<@Pattern(regexp = DECISIONS) String> decision,
            @RequestParam(required = false) @Size(max = 8) List<@Pattern(regexp = EFFECTS) String> effect,
            @RequestParam(required = false) @Size(max = 200) List<UUID> employeeId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        List<Column<EventRow>> columns = List.of(
                new Column<>("Time", r -> iso.format(time.zoned(r.occurredAt()).toOffsetDateTime())),
                new Column<>("Camera", r -> r.camera().code()),
                new Column<>("Direction", EventRow::direction),
                new Column<>("Person", CrossingController::person),
                new Column<>(
                        "Employee ID",
                        r -> r.person() == null ? null : r.person().employeeId()),
                new Column<>("Method", r -> r.method() == null ? null : METHOD_LABELS.get(r.method())),
                new Column<>("Decision", r -> DECISION_LABELS.get(r.decision())),
                new Column<>("Punch effect", r -> r.effect() == null ? null : EFFECT_LABELS.get(r.effect())),
                new Column<>("Rule reason", EventRow::effectReason),
                new Column<>("Event ID", EventRow::code));
        var filter =
                new CrossingService.ListFilter(from, to, camera, direction, method, decision, effect, employeeId, q);
        return csv.export("events", "Events", columns, crossings.export(filter, sort));
    }

    @Operation(summary = "A crossing with its attendance day, HRMS punches and changes")
    @RequiresPermission(Permission.EVENTS_VIEW)
    @GetMapping("/{code}")
    public CrossingViews.EventDetail detail(@PathVariable @Pattern(regexp = CODE) String code) {
        return crossings.detail(code);
    }

    @Operation(summary = "Add the identified crossing's face crop as the employee's face template")
    @RequiresPermission({Permission.EVENTS_VIEW, Permission.PEOPLE_ENROL})
    @PostMapping("/{code}/template")
    public CrossingViews.EventDetail addTemplate(@PathVariable @Pattern(regexp = CODE) String code) {
        return crossings.addTemplate(code);
    }

    private static String person(EventRow r) {
        if (r.person() != null) {
            return r.person().name();
        }
        return r.unknownLabel() == null ? "Unknown face" : r.unknownLabel();
    }
}
