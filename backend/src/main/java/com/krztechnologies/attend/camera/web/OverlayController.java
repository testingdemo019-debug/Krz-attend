package com.krztechnologies.attend.camera.web;

import com.krztechnologies.attend.camera.service.OverlayRelay;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live View overlays: the worker's tracks of one camera as server-sent events. */
@RestController
@RequestMapping("/api/v1/realtime")
@Validated
public class OverlayController {

    private final OverlayRelay overlays;
    private final CurrentUser currentUser;

    public OverlayController(OverlayRelay overlays, CurrentUser currentUser) {
        this.overlays = overlays;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Server-sent `overlay` events of one camera: tracks, labels, confidence bands (never scores)")
    @RequiresPermission(Permission.LIVE_VIEW)
    @GetMapping(path = "/overlays", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter overlays(@RequestParam @Pattern(regexp = "^CAM-\\d{2,}$") String camera) {
        return overlays.subscribe(currentUser.employeeId(), camera);
    }
}
