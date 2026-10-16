package com.krztechnologies.attend.audit.web;

import com.krztechnologies.attend.audit.service.AuditLogService;
import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Settings → Audit log: the read-only list (newest first unless sorted otherwise), its filter counts and CSV export. */
@RestController
@RequestMapping("/api/v1/audit")
@Validated
public class AuditLogController {

    private final AuditLogService audit;

    public AuditLogController(AuditLogService audit) {
        this.audit = audit;
    }

    @Operation(
            summary = "Audit entries; filters: category and actor (employee ID or recorded name), several of each,"
                    + " from/to (IST days, inclusive), q (free text); sort: ts (default, newest first), actor, category,"
                    + " action")
    @RequiresPermission(Permission.AUDIT_VIEW)
    @GetMapping
    public PageResponse<AuditLogService.AuditEntry> list(
            @RequestParam(required = false) @Size(max = 15) List<AuditCategory> category,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 200) String> actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return audit.list(
                new AuditLogService.AuditQuery(category, actor, from, to, q),
                AuditLogService.pageable(page, size, sort));
    }

    @Operation(summary = "The list's filter options with their counts over the date range and text")
    @RequiresPermission(Permission.AUDIT_VIEW)
    @GetMapping("/facets")
    public AuditLogService.Facets facets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 200) String q) {
        return audit.facets(new AuditLogService.AuditQuery(null, null, from, to, q));
    }

    @Operation(summary = "CSV of the audit entries with the list's filters and order (audited)")
    @RequiresPermission({Permission.AUDIT_VIEW, Permission.EVENTS_EXPORT})
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 15) List<AuditCategory> category,
            @RequestParam(required = false) @Size(max = 100) List<@Size(max = 200) String> actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) String sort) {
        return audit.export(
                new AuditLogService.AuditQuery(category, actor, from, to, q),
                AuditLogService.pageable(0, 1, sort).getSort());
    }
}
