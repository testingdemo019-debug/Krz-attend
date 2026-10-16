package com.krztechnologies.attend.dev;

import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dev only: inject one synthetic crossing into the real pipeline. */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
public class DevCrossingController {

    private final DevCrossings crossings;

    public DevCrossingController(DevCrossings crossings) {
        this.crossings = crossings;
    }

    /**
     * @param camera a camera code; the first enabled camera when missing
     * @param employeeId the person; an unknown face when missing
     */
    public record InjectRequest(
            @Pattern(regexp = "^CAM-\\d{2,}$") String camera,
            @NotNull @Pattern(regexp = "IN|OUT") String direction,
            UUID employeeId) {}

    @Operation(summary = "Dev profile only: write a synthetic crossing message into krz:crossings")
    @RequiresPermission(Permission.SETTINGS_INTEGRATIONS)
    @PostMapping("/crossings")
    public Map<String, UUID> inject(@Valid @RequestBody InjectRequest request) {
        return Map.of("crossingUid", crossings.inject(request.camera(), request.direction(), request.employeeId()));
    }
}
