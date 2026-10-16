package com.krztechnologies.attend.hrms.web;

import com.krztechnologies.attend.common.export.CsvExportService;
import com.krztechnologies.attend.common.export.CsvExportService.Column;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.hrms.service.HrmsOutboxService;
import com.krztechnologies.attend.hrms.service.HrmsViews;
import com.krztechnologies.attend.hrms.service.HrmsViews.Entry;
import com.krztechnologies.attend.rules.service.OutboxStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Settings → KRZ HRMS: connection, payload format and the punch outbox with retry and release. */
@RestController
@RequestMapping("/api/v1/settings/hrms")
@Validated
public class HrmsController {

    private static final String CODE = "^OB-\\d{6,}$";

    private final HrmsOutboxService outbox;
    private final CsvExportService csv;
    private final TimeService time;

    public HrmsController(HrmsOutboxService outbox, CsvExportService csv, TimeService time) {
        this.outbox = outbox;
        this.csv = csv;
        this.time = time;
    }

    @Operation(
            summary = "Connection (\"Not connected — dummy\"), retries, master data sources, payload samples, counts")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping
    public HrmsViews.Overview overview() {
        return outbox.overview();
    }

    @Operation(summary = "The outbox: entries by status, kind, shift day and text")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/outbox")
    public PageResponse<Entry> list(
            @RequestParam(required = false) @Size(max = 6) List<OutboxStatus> status,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "PUNCH|VOID") String> kind,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return outbox.list(new HrmsOutboxService.ListFilter(status, kind, from, to, q), page, size, sort);
    }

    @Operation(summary = "The outbox entries of the filter as CSV (at most 50,000)")
    @RequiresPermission({Permission.SETTINGS_INTEGRATIONS, Permission.EVENTS_EXPORT})
    @GetMapping("/outbox/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 6) List<OutboxStatus> status,
            @RequestParam(required = false) @Size(max = 2) List<@Pattern(regexp = "PUNCH|VOID") String> kind,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String sort) {
        DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        List<Column<Entry>> columns = List.of(
                new Column<>(
                        "Created", e -> iso.format(time.zoned(e.createdAt()).toOffsetDateTime())),
                new Column<>("Punch", e -> e.punchId() + " " + e.type()),
                new Column<>(
                        "Employee",
                        e -> e.employee() == null ? null : e.employee().name()),
                new Column<>("Shift day", Entry::shiftDay),
                new Column<>("Status", Entry::statusLabel),
                new Column<>("Attempts", Entry::attempts));
        var filter = new HrmsOutboxService.ListFilter(status, kind, from, to, q);
        return csv.export("hrms-outbox", "KRZ HRMS outbox", columns, outbox.export(filter, sort));
    }

    @Operation(summary = "Queue a failed entry again with fresh attempts (audited)")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/outbox/{code}/retry")
    public Entry retry(@PathVariable @Pattern(regexp = CODE) String code) {
        return outbox.retry(code);
    }

    @Operation(summary = "Queue every failed entry again (audited)")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/outbox/retry-failed")
    public HrmsViews.Requeued retryFailed() {
        return outbox.retryFailed();
    }

    @Operation(summary = "Send the punches held while HRMS was not connected, oldest first (only when HRMS is enabled)")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/outbox/release-held")
    public HrmsViews.Requeued releaseHeld() {
        return outbox.releaseHeld();
    }
}
