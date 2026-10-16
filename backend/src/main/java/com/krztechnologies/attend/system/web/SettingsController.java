package com.krztechnologies.attend.system.web;

import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.PublicEndpoint;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.AttendanceRulesSettings;
import com.krztechnologies.attend.common.settings.GeneralSettings;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.system.service.GeneralSettingsService;
import com.krztechnologies.attend.system.service.LogoService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Settings → General (with the logo and its public URL) and Settings → Attendance rules. */
@RestController
@RequestMapping("/api/v1")
public class SettingsController {

    private final GeneralSettingsService general;
    private final LogoService logo;
    private final SettingsService settings;

    public SettingsController(GeneralSettingsService general, LogoService logo, SettingsService settings) {
        this.general = general;
        this.logo = logo;
        this.settings = settings;
    }

    /**
     * @param timeFormat {@code 12h} or {@code 24h}
     * @param employeeIdPattern regular expression every HRMS Employee ID must match
     */
    public record UpdateGeneralRequest(
            @NotBlank @Size(max = 120) String orgName,
            @NotNull ZoneId timezone,
            @NotBlank @Size(max = 20) String dateFormat,
            @NotBlank @Pattern(regexp = "12h|24h") String timeFormat,
            @NotBlank @Size(max = 100) String employeeIdPattern,
            @NotNull @Min(3) @Max(60) Integer undoWindowSeconds,
            @NotNull Integer version) {}

    public record RulesView(AttendanceRulesSettings settings, int version) {}

    public record UpdateRulesRequest(
            @NotNull @Min(0) @Max(30) Integer duplicateMinutes,
            @NotNull @Min(0) @Max(600) Integer turnedBackSeconds,
            @NotNull @Min(1) @Max(12) Integer absentCutoffHours,
            @NotNull AttendanceRulesSettings.WorkedHours workedHours,
            @NotNull AttendanceRulesSettings.PushMode pushMode,
            @NotNull Integer version) {}

    @Operation(summary = "Settings → General: organisation name, time zone, formats, Employee ID pattern, undo window")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/settings/general")
    public GeneralSettingsService.GeneralView getGeneral() {
        return general.view();
    }

    @Operation(summary = "Save Settings → General (409 STALE_VERSION when someone else saved first)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/settings/general")
    public GeneralSettingsService.GeneralView updateGeneral(@Valid @RequestBody UpdateGeneralRequest request) {
        return general.update(
                new GeneralSettings(
                        request.orgName().trim(),
                        request.timezone(),
                        request.dateFormat(),
                        request.timeFormat(),
                        request.employeeIdPattern(),
                        request.undoWindowSeconds()),
                request.version());
    }

    @Operation(
            summary = "Upload the logo: multipart part \"file\", PNG or JPEG up to 512 KB (never SVG); re-encoded"
                    + " server-side")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/settings/general/logo")
    public LogoService.LogoView uploadLogo(@RequestParam(name = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new Errors.BadRequest("NO_FILE", "Choose a PNG or JPEG file.");
        }
        try {
            return logo.upload(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Operation(summary = "Remove the logo (the UI shows the \"KRZ\" text mark again)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @DeleteMapping("/settings/general/logo")
    public ResponseEntity<Void> removeLogo() {
        logo.remove();
        return ResponseEntity.noContent().build();
    }

    /** Spring answers 304 Not Modified itself when the request's If-None-Match matches the ETag. */
    @Operation(summary = "The uploaded logo (PNG or JPEG, ETag, nosniff); 404 when there is none")
    @PublicEndpoint
    @GetMapping("/public/logo")
    public ResponseEntity<byte[]> publicLogo() {
        var current = logo.current().orElseThrow(() -> new Errors.NotFound("No logo has been uploaded."));
        return ResponseEntity.ok()
                .eTag(current.etag())
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(current.contentType()))
                .body(current.content());
    }

    @Operation(
            summary = "Settings → Attendance rules: duplicates, turned back, absent cut-off, worked hours, push mode")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/settings/rules")
    public RulesView getRules() {
        var current = settings.get(SettingsArea.ATTENDANCE_RULES);
        return new RulesView(current.value(), current.version());
    }

    @Operation(summary = "Save Settings → Attendance rules (409 STALE_VERSION when someone else saved first)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/settings/rules")
    public RulesView updateRules(@Valid @RequestBody UpdateRulesRequest request) {
        var saved = settings.update(
                SettingsArea.ATTENDANCE_RULES,
                new AttendanceRulesSettings(
                        request.duplicateMinutes(),
                        request.turnedBackSeconds(),
                        request.absentCutoffHours(),
                        request.workedHours(),
                        request.pushMode()),
                request.version());
        return new RulesView(saved.value(), saved.version());
    }
}
