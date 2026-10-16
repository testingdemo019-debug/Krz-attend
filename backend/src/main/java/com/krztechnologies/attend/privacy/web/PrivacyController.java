package com.krztechnologies.attend.privacy.web;

import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.PrivacySettings;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.privacy.domain.PrivacyRequest;
import com.krztechnologies.attend.privacy.service.ConsentNoticeService;
import com.krztechnologies.attend.privacy.service.PrivacyRequestService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settings → Privacy &amp; retention: retention periods, the consent notice and requests from employees. */
@RestController
@RequestMapping("/api/v1/settings/privacy")
public class PrivacyController {

    private final SettingsService settings;
    private final ConsentNoticeService notices;
    private final PrivacyRequestService requests;

    public PrivacyController(SettingsService settings, ConsentNoticeService notices, PrivacyRequestService requests) {
        this.settings = settings;
        this.notices = notices;
        this.requests = requests;
    }

    public record PrivacyView(PrivacySettings settings, int version) {}

    public record UpdatePrivacyRequest(
            @NotNull @Min(1) @Max(365) Integer cropDays,
            @NotNull @Min(1) @Max(720) Integer unknownHours,
            @NotNull @Min(30) @Max(3650) Integer eventDays,
            @NotNull @Min(30) @Max(3650) Integer auditDays,
            @NotNull Integer version) {}

    public record PublishNoticeRequest(
            @NotBlank @Size(min = 80, max = 10_000) String text) {}

    /**
     * @param receivedOn defaults to today (IST); not in the future
     */
    public record CreatePrivacyRequest(
            @NotNull UUID employeeId,
            @NotNull PrivacyRequest.Type type,
            @Nullable LocalDate receivedOn,
            @Nullable @Size(max = 1000) String note) {}

    /**
     * @param outcome what was done, or (required) why it was declined
     */
    public record UpdatePrivacyRequestBody(
            @NotNull PrivacyRequest.Status status,
            @Nullable @Size(max = 1000) String outcome,
            @NotNull Integer version) {}

    @Operation(summary = "Retention periods: entrance crops, unknown faces, crossings, audit log")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @GetMapping
    public PrivacyView get() {
        var current = settings.get(SettingsArea.PRIVACY);
        return new PrivacyView(current.value(), current.version());
    }

    @Operation(summary = "Save the retention periods (409 STALE_VERSION when someone else saved first)")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @PutMapping
    public PrivacyView update(@Valid @RequestBody UpdatePrivacyRequest request) {
        var saved = settings.update(
                SettingsArea.PRIVACY,
                new PrivacySettings(
                        request.cropDays(), request.unknownHours(), request.eventDays(), request.auditDays()),
                request.version());
        return new PrivacyView(saved.value(), saved.version());
    }

    @Operation(summary = "Consent notice versions, newest first, and how many consents refer to an older version")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @GetMapping("/notices")
    public ConsentNoticeService.NoticesView notices() {
        return notices.list();
    }

    @Operation(summary = "Publish a new consent notice version (the previous one is deactivated)")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @PostMapping("/notices")
    public ConsentNoticeService.NoticeView publishNotice(@Valid @RequestBody PublishNoticeRequest request) {
        return notices.publish(request.text());
    }

    @Operation(summary = "Requests from employees about their data (access, correction, erasure), newest first")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @GetMapping("/requests")
    public List<PrivacyRequestService.PrivacyRequestView> listRequests(
            @RequestParam(required = false) PrivacyRequest.Status status) {
        return requests.list(status);
    }

    @Operation(summary = "Record a request received from an employee (PR-nnn, due 30 days after receipt)")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @PostMapping("/requests")
    public PrivacyRequestService.PrivacyRequestView createRequest(@Valid @RequestBody CreatePrivacyRequest request) {
        return requests.create(request.employeeId(), request.type(), request.receivedOn(), request.note());
    }

    @Operation(summary = "Move a request on: Open → In progress → Completed, or Declined with a reason")
    @RequiresPermission(Permission.SETTINGS_PRIVACY)
    @PutMapping("/requests/{code}")
    public PrivacyRequestService.PrivacyRequestView updateRequest(
            @PathVariable @Pattern(regexp = "^PR-\\d{3,}$") String code,
            @Valid @RequestBody UpdatePrivacyRequestBody request) {
        return requests.update(code, request.status(), request.outcome(), request.version());
    }
}
