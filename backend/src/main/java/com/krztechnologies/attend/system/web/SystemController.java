package com.krztechnologies.attend.system.web;

import com.krztechnologies.attend.attendance.service.DayCloseService;
import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.security.SignedIn;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.recognition.service.ModelInfoService;
import com.krztechnologies.attend.recognition.service.RecognitionViews;
import com.krztechnologies.attend.system.service.DiagnosticsService;
import com.krztechnologies.attend.system.service.SystemStatusService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.boot.SpringBootVersion;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settings → System and the topbar status pill. */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemStatusService status;
    private final DiagnosticsService diagnostics;
    private final DayCloseService dayClose;
    private final CurrentUser currentUser;
    private final AppProperties app;
    private final ModelInfoService models;

    public SystemController(
            SystemStatusService status,
            DiagnosticsService diagnostics,
            DayCloseService dayClose,
            CurrentUser currentUser,
            AppProperties app,
            ModelInfoService models) {
        this.status = status;
        this.diagnostics = diagnostics;
        this.dayClose = dayClose;
        this.currentUser = currentUser;
        this.app = app;
        this.models = models;
    }

    /**
     * Versions for Settings → System → About.
     *
     * @param worker the recognition worker's version and its models with their licences (the only place licences are
     *     shown)
     */
    public record About(
            String appVersion, String environment, String java, String springBoot, RecognitionViews.ModelInfo worker) {}

    @Operation(summary = "The status pill and the summarised health rows (every signed-in user)")
    @SignedIn
    @GetMapping("/status/summary")
    public SystemStatusService.Summary summary() {
        return status.summary();
    }

    @Operation(summary = "Every service with its status (database, Redis, e-mail, HRMS, push, directory, disk, backup)")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/status")
    public List<SystemStatusService.StatusRow> status() {
        return status.status();
    }

    @Operation(summary = "Day close runs, newest first")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/day-close-runs")
    public PageResponse<DayCloseService.RunView> dayCloseRuns(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return dayClose.runs(page, size);
    }

    @Operation(summary = "Run day close now for every (shift, day) that is due; running it again does nothing")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/day-close/run")
    public List<DayCloseService.RunView> runDayClose() {
        var me = currentUser.require();
        return dayClose.closeDue(
                DayCloseService.Trigger.MANUAL, AuditActor.employee(me.employeeId(), me.displayName()));
    }

    @Operation(summary = "Versions, and the worker's models with their licences")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/about")
    public About about() {
        return new About(
                app.version(),
                app.env().name(),
                Runtime.version().toString(),
                SpringBootVersion.getVersion(),
                models.current());
    }

    @Operation(
            summary = "Download diagnostics (zip: versions, settings without secrets, service status, the last 200"
                    + " WARN/ERROR log lines masked); audited")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/diagnostics")
    public ResponseEntity<byte[]> diagnostics() {
        DiagnosticsService.Diagnostics download = diagnostics.build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName())
                                .build()
                                .toString())
                .cacheControl(CacheControl.noStore())
                .body(download.zip());
    }
}
