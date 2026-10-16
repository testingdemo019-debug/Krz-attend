package com.krztechnologies.attend.notification.web;

import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.security.SignedIn;
import com.krztechnologies.attend.common.web.PageRequestSpec;
import com.krztechnologies.attend.common.web.PageResponse;
import com.krztechnologies.attend.notification.domain.NotificationRule;
import com.krztechnologies.attend.notification.service.InboxService;
import com.krztechnologies.attend.notification.service.NotificationSettingsService;
import com.krztechnologies.attend.notification.service.TestEmailService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The bell (the user's own notifications), Settings → Notifications and the test e-mail. */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private static final String ROLE_CODE = "^ROLE-[A-Z0-9-]{2,30}$";

    private final InboxService inbox;
    private final TestEmailService testEmail;
    private final NotificationSettingsService rules;
    private final CurrentUser currentUser;

    public NotificationController(
            InboxService inbox,
            TestEmailService testEmail,
            NotificationSettingsService rules,
            CurrentUser currentUser) {
        this.inbox = inbox;
        this.testEmail = testEmail;
        this.rules = rules;
        this.currentUser = currentUser;
    }

    @Operation(summary = "The signed-in user's notifications, newest first")
    @SignedIn
    @GetMapping("/notifications")
    public PageResponse<InboxService.InboxItem> list(
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var pageable = PageRequestSpec.toPageable(page, size, null, Map.of(), Sort.unsorted());
        return inbox.list(currentUser.employeeId(), unread, pageable);
    }

    @Operation(summary = "Number of unread notifications (the bell badge)")
    @SignedIn
    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", inbox.unreadCount(currentUser.employeeId()));
    }

    @Operation(summary = "Mark one notification as read")
    @SignedIn
    @PostMapping("/notifications/{code}/read")
    public ResponseEntity<Void> markRead(@PathVariable @Pattern(regexp = "^NT-\\d{6,}$") String code) {
        inbox.markRead(currentUser.employeeId(), code);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark all of the user's notifications as read")
    @SignedIn
    @PostMapping("/notifications/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", inbox.markAllRead(currentUser.employeeId()));
    }

    /**
     * @param push must be false: push is shown but not available yet
     * @param threshold only for rules with a threshold (1–240 in the rule's unit)
     * @param quietStart IST; with {@code quietEnd}, or both null
     * @param digestTime IST; required for {@code DAILY}
     * @param params null keeps them; otherwise exactly the rule's keys as {@code HH:mm} (NR-05 office hours)
     * @param roleIds role codes (ROLE-…)
     */
    public record UpdateRuleRequest(
            @NotNull Boolean enabled,
            @Nullable @Min(1) @Max(240) Integer threshold,
            @NotNull Boolean inApp,
            @NotNull Boolean email,
            @NotNull Boolean push,
            @Nullable LocalTime quietStart,
            @Nullable LocalTime quietEnd,
            @NotNull NotificationRule.Digest digest,
            @Nullable LocalTime digestTime,
            @Nullable Map<String, String> params,
            @NotNull @Size(max = 50) List<@Pattern(regexp = ROLE_CODE) String> roleIds,
            @NotNull @Size(max = 200) List<@NotNull UUID> userIds,
            @NotNull Integer version) {}

    @Operation(
            summary = "Settings → Notifications: every rule with channels, threshold, quiet hours, digest, recipients")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @GetMapping("/settings/notifications")
    public NotificationSettingsService.NotificationSettingsView settings() {
        return rules.view();
    }

    @Operation(summary = "Save one notification rule (409 STALE_VERSION when someone else saved it first)")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PutMapping("/settings/notifications/{ruleCode}")
    public NotificationSettingsService.RuleView updateRule(
            @PathVariable @Pattern(regexp = "^NR-\\d{2}$") String ruleCode,
            @Valid @RequestBody UpdateRuleRequest request) {
        return rules.update(
                ruleCode,
                new NotificationSettingsService.RuleChange(
                        request.enabled(),
                        request.threshold(),
                        request.inApp(),
                        request.email(),
                        request.push(),
                        request.quietStart(),
                        request.quietEnd(),
                        request.digest(),
                        request.digestTime(),
                        request.params(),
                        request.roleIds(),
                        request.userIds(),
                        request.version()));
    }

    /**
     * @param to only {@code me}: the e-mail goes to the signed-in user (or MAIL_TEST_RECIPIENT in development)
     */
    public record TestEmailRequest(@Pattern(regexp = "me") String to) {}

    @Operation(summary = "Send a test e-mail to yourself now; reports the provider's exact error code")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/settings/notifications/test-email")
    public TestEmailService.TestEmailResult sendTestEmail(@Valid @RequestBody TestEmailRequest request) {
        return testEmail.sendToSelf(currentUser.employeeId());
    }
}
