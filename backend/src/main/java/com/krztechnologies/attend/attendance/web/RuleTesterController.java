package com.krztechnologies.attend.attendance.web;

import com.krztechnologies.attend.attendance.service.RuleTesterService;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.common.settings.AttendanceRulesSettings;
import com.krztechnologies.attend.rules.service.Direction;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings → Attendance rules → Rule tester. */
@RestController
@RequestMapping("/api/v1/settings/rules")
@Validated
public class RuleTesterController {

    private final RuleTesterService tester;

    public RuleTesterController(RuleTesterService tester) {
        this.tester = tester;
    }

    /** @param time {@code HH:mm} or {@code HH:mm:ss} (turn-backs are counted in seconds) */
    public record RowRequest(
            @NotNull LocalTime time,
            @NotNull Direction direction,
            @Nullable Boolean nextDay) {}

    /**
     * @param rules the page's unsaved rules; omit to test the saved ones
     * @param holiday evaluate as a public holiday (default no)
     * @param afterClose true = after day close (final); false or omitted = right after the last crossing (live)
     */
    public record TestRequest(
            @NotNull @Pattern(regexp = "^[A-Z0-9-]{2,20}$") String shiftCode,
            @Nullable Boolean holiday,
            @Nullable Boolean afterClose,
            @NotNull @Size(max = 30) List<@Valid @NotNull RowRequest> rows,
            @Nullable @Valid AttendanceRulesSettings rules) {}

    @Operation(summary = "Run the punch rules on a scenario day (saved or draft rules); nothing is stored")
    @RequiresPermission(Permission.SETTINGS_ATTENDANCE)
    @PostMapping("/test")
    public RuleTesterService.Result test(@Valid @RequestBody TestRequest request) {
        return tester.test(new RuleTesterService.Scenario(
                request.shiftCode(),
                Boolean.TRUE.equals(request.holiday()),
                Boolean.TRUE.equals(request.afterClose()),
                request.rows().stream()
                        .map(r -> new RuleTesterService.Row(r.time(), r.direction(), Boolean.TRUE.equals(r.nextDay())))
                        .toList(),
                request.rules()));
    }
}
