package com.krztechnologies.attend.camera.web;

import com.krztechnologies.attend.camera.service.CameraViews;
import com.krztechnologies.attend.camera.service.LiveViewService;
import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live View: the camera tiles and the snapshot audit. */
@RestController
@RequestMapping("/api/v1/live")
public class LiveController {

    private final LiveViewService live;

    public LiveController(LiveViewService live) {
        this.live = live;
    }

    /** @param camera the camera code of the tile the snapshot was taken from */
    public record SnapshotAuditRequest(
            @NotNull @Pattern(regexp = "^CAM-\\d{2,}$") String camera) {}

    @Operation(summary = "Live View tiles: status, frame rate, latency, direction mode, tripwire and zone")
    @RequiresPermission(Permission.LIVE_VIEW)
    @GetMapping("/cameras")
    public List<CameraViews.LiveTile> cameras() {
        return live.tiles();
    }

    @Operation(summary = "The browser saved a snapshot of a tile (audit only)")
    @RequiresPermission(Permission.LIVE_VIEW)
    @PostMapping("/snapshot-audit")
    public ResponseEntity<Void> snapshotAudit(@Valid @RequestBody SnapshotAuditRequest request) {
        live.snapshotSaved(request.camera());
        return ResponseEntity.noContent().build();
    }
}
