package com.krztechnologies.attend.directory.web;

import com.krztechnologies.attend.common.audit.AuditActor;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.SignInSettings;
import com.krztechnologies.attend.directory.domain.DirectorySyncRun;
import com.krztechnologies.attend.directory.service.DirectorySyncService;
import com.krztechnologies.attend.directory.service.SignInSettingsService;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Directory sync and Settings → Microsoft sign-in. */
@RestController
@RequestMapping("/api/v1")
public class DirectoryController {

    private final DirectorySyncService sync;
    private final SignInSettingsService signIn;
    private final EmployeeDirectory employees;
    private final CurrentUser currentUser;

    public DirectoryController(
            DirectorySyncService sync,
            SignInSettingsService signIn,
            EmployeeDirectory employees,
            CurrentUser currentUser) {
        this.sync = sync;
        this.signIn = signIn;
        this.employees = employees;
        this.currentUser = currentUser;
    }

    /**
     * The Employees page's line under the title.
     *
     * @param lastSyncAt when the latest sync finished (or started, while it runs); null before the first
     * @param lastSyncStatus {@code SUCCESS}, {@code WARNING} or {@code FAILED}; null while it runs
     * @param missingEmployeeIds enabled employees without an HRMS Employee ID
     * @param missingEmployment enabled employees whose employment details are missing (R16)
     */
    public record Summary(
            @Nullable Instant lastSyncAt,
            @Nullable String lastSyncStatus,
            int lastSyncWarnings,
            int missingEmployeeIds,
            int missingEmployment) {}

    @Operation(summary = "The last directory sync and how many enabled employees have no Employee ID (Employees)")
    @RequiresPermission(Permission.PEOPLE_VIEW)
    @GetMapping("/directory/summary")
    public Summary summary() {
        var last = sync.latest();
        return new Summary(
                last.map(r -> r.finishedAt() == null ? r.startedAt() : r.finishedAt())
                        .orElse(null),
                last.map(DirectorySyncService.SyncRunView::status).orElse(null),
                last.map(r -> r.warnings().size()).orElse(0),
                employees.missingEmployeeIds(),
                employees.missingEmployment());
    }

    public record UpdateSignInRequest(
            @NotNull @Min(5) @Max(480) Integer idleTimeoutMin,
            @NotNull @Min(1) @Max(24) Integer syncEvery,
            @NotNull @Min(1) @Max(365) Integer eraseAfterDays,
            @NotNull Integer version) {}

    @Operation(summary = "Run the directory sync now (Sync now)")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/directory/sync")
    public DirectorySyncService.SyncRunView syncNow() {
        var me = currentUser.require();
        return sync.run(DirectorySyncRun.Trigger.MANUAL, AuditActor.employee(me.employeeId(), me.displayName()));
    }

    @Operation(
            summary = "Enabled member accounts in the tenant but in none of the sync-scope groups (they cannot be given"
                    + " access yet); e-mails only with people.view.sensitive")
    @RequiresPermission(
            value = {Permission.SETTINGS_ROLES, Permission.SETTINGS_INTEGRATIONS},
            anyOf = true)
    @GetMapping("/directory/unsynced")
    public DirectorySyncService.Unsynced unsynced() {
        return sync.unsynced(currentUser.has(Permission.PEOPLE_VIEW_SENSITIVE));
    }

    @Operation(summary = "The last 50 directory sync runs")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/directory/sync-runs")
    public List<DirectorySyncService.SyncRunView> syncRuns() {
        return sync.history();
    }

    @Operation(summary = "Microsoft sign-in: read-only connection facts and the editable session and sync settings")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @GetMapping("/settings/signin")
    public SignInSettingsService.SignInView getSignIn() {
        return signIn.view();
    }

    @Operation(summary = "Save idle timeout, sync interval and erase-after days")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PutMapping("/settings/signin")
    public SignInSettingsService.SignInView updateSignIn(@Valid @RequestBody UpdateSignInRequest request) {
        return signIn.update(
                new SignInSettings(request.idleTimeoutMin(), request.syncEvery(), request.eraseAfterDays()),
                request.version());
    }

    @Operation(
            summary =
                    "Test the connection: app token + one Graph read; reports Microsoft's error code, never the secret")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/settings/signin/test")
    public SignInSettingsService.TestResult testSignIn() {
        return signIn.test();
    }
}
