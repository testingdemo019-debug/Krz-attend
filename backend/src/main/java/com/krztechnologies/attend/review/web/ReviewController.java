package com.krztechnologies.attend.review.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.review.service.ReviewService;
import com.krztechnologies.attend.review.service.ReviewViews;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** The Review Queue: open items by type, resolved history, decisions, bulk decisions and undo. */
@RestController
@RequestMapping("/api/v1/review")
@Validated
public class ReviewController {

    private static final String CODE = "^RV-\\d{4,}$";
    private static final String TYPES = "UNKNOWN|UNCERTAIN|REID|LIVENESS";
    private static final String ACTIONS = "CONFIRM|ASSIGN|NOT_EMPLOYEE|REJECT|SPOOF|FALSE_ALARM|EXPIRED";
    private static final String RESOLVER =
            "SYSTEM|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final ReviewService review;
    private final CsvExportService csv;
    private final TimeService time;

    public ReviewController(ReviewService review, CsvExportService csv, TimeService time) {
        this.review = review;
        this.csv = csv;
        this.time = time;
    }

    /**
     * A decision on one item.
     *
     * @param employeeId ASSIGN, or FALSE_ALARM for someone other than the top candidate
     * @param reason Correct identity / Wrong suggestion / Other (overrides), Visitor / Wrong suggestion / Other (not an
     *     employee)
     * @param addTemplate add the best crop as a face template (consent and quality permitting)
     * @param sightingCode the crossing to use as the best crop (unknown faces)
     */
    public record DecisionRequest(
            @NotNull ReviewService.Action action,
            @Nullable UUID employeeId,
            @Nullable @Size(max = 100) String reason,
            @Nullable @Size(max = 500) String note,
            @Nullable Boolean addTemplate,
            @Nullable @Pattern(regexp = "^EV-\\d{6,}$") String sightingCode) {}

    public record BulkRequest(
            @NotEmpty @Size(max = 200) List<@NotNull @Pattern(regexp = CODE) String> codes,
            @NotNull ReviewService.Action action,
            @Nullable UUID employeeId,
            @Nullable @Size(max = 100) String reason) {}

    public record UndoRequest(@NotBlank @Size(max = 64) String undoToken) {}

    @Operation(
            summary = "Review items: open ones oldest first (filters by type, direction, confidence, waiting), or"
                    + " the others (status closed = resolved and expired) sorted, newest resolution first")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @GetMapping
    public PageResponse<ReviewViews.Item> list(
            @RequestParam(required = false) @Size(max = 4) List<@Pattern(regexp = TYPES) String> type,
            @RequestParam(required = false) @Pattern(regexp = "open|resolved|expired|closed") String status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 6) List<@Pattern(regexp = ACTIONS) String> action,
            @RequestParam(required = false) @Size(max = 50) List<@Pattern(regexp = RESOLVER) String> resolvedBy,
            @RequestParam(required = false) @Pattern(regexp = "IN|OUT") String direction,
            @RequestParam(required = false) @Pattern(regexp = "HIGH|MEDIUM|LOW") String band,
            @RequestParam(defaultValue = "false") boolean waiting,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return review.list(
                new ReviewService.Filter(
                        type,
                        status == null ? null : status.toUpperCase(Locale.ROOT),
                        from,
                        to,
                        q,
                        action,
                        resolvedBy,
                        direction,
                        band,
                        waiting),
                page,
                size,
                sort);
    }

    @Operation(summary = "The Resolved tab's filter options with their counts (types, resolutions, reviewers)")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @GetMapping("/facets")
    public ReviewViews.Facets facets() {
        return review.facets();
    }

    @Operation(summary = "The Resolved tab's items of the filter as CSV (at most 50,000)")
    @RequiresPermission({Permission.REVIEW_RESOLVE, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 4) List<@Pattern(regexp = TYPES) String> type,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 6) List<@Pattern(regexp = ACTIONS) String> action,
            @RequestParam(required = false) @Size(max = 50) List<@Pattern(regexp = RESOLVER) String> resolvedBy,
            @RequestParam(required = false) String sort) {
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        Function<Instant, String> at = instant ->
                instant == null ? null : iso.format(time.zoned(instant).toOffsetDateTime());
        List<Column<ReviewViews.Item>> columns = List.of(
                new Column<>("Item", i -> i.code() + " " + i.typeLabel()),
                new Column<>("Seen", i -> at.apply(i.createdAt())),
                new Column<>(
                        "Resolution",
                        i -> i.resolution() == null ? null : i.resolution().label()),
                new Column<>(
                        "Employee",
                        i -> i.resolution() == null || i.resolution().employee() == null
                                ? null
                                : i.resolution().employee().name()),
                new Column<>(
                        "Resolved by",
                        i -> i.resolution() == null ? null : i.resolution().by()),
                new Column<>(
                        "Resolved at",
                        i -> i.resolution() == null
                                ? null
                                : at.apply(i.resolution().at())));
        var filter = new ReviewService.Filter(type, "CLOSED", from, to, q, action, resolvedBy, null, null, false);
        return csv.export("review-history", "Review history", columns, review.export(filter, sort));
    }

    @Operation(summary = "Open items per type, and how many are resolved")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @GetMapping("/counts")
    public ReviewViews.Counts counts() {
        return review.counts();
    }

    @Operation(summary = "One item with its crossings, candidates and template eligibility")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @GetMapping("/{code}")
    public ReviewViews.Item item(@PathVariable @Pattern(regexp = CODE) String code) {
        return review.item(code);
    }

    @Operation(summary = "Decide an item; the crossings go through the punch rules; returns an undo token")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @PostMapping("/{code}/decision")
    public ReviewViews.Applied decide(
            @PathVariable @Pattern(regexp = CODE) String code, @Valid @RequestBody DecisionRequest request) {
        return review.decide(
                code,
                new ReviewService.DecisionInput(
                        request.action(),
                        request.employeeId(),
                        request.reason(),
                        request.note(),
                        Boolean.TRUE.equals(request.addTemplate()),
                        request.sightingCode()));
    }

    @Operation(summary = "The same decision on several items; one undo token for all")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @PostMapping("/bulk")
    public ReviewViews.Applied bulk(@Valid @RequestBody BulkRequest request) {
        return review.bulk(request.codes(), request.action(), request.employeeId(), request.reason());
    }

    @Operation(summary = "Undo a decision within the undo window (only the person who made it)")
    @RequiresPermission(Permission.REVIEW_RESOLVE)
    @PostMapping("/undo")
    public ResponseEntity<Void> undo(@Valid @RequestBody UndoRequest request) {
        review.undo(request.undoToken());
        return ResponseEntity.noContent().build();
    }
}
