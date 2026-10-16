package com.krztechnologies.attend.crossing.web;

import com.krztechnologies.attend.common.security.Permission;
import com.krztechnologies.attend.common.security.RequiresPermission;
import com.krztechnologies.attend.crossing.service.CrossingService;
import com.krztechnologies.attend.crossing.service.CrossingViews;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Live View's crossing feed (the tiles and overlays are the camera module's). */
@RestController
@RequestMapping("/api/v1/live")
@Validated
public class LiveCrossingController {

    private final CrossingService crossings;

    public LiveCrossingController(CrossingService crossings) {
        this.crossings = crossings;
    }

    @Operation(summary = "The newest crossings (\"Last 10 crossings\"), of one camera or all")
    @RequiresPermission(Permission.LIVE_VIEW)
    @GetMapping("/recent-crossings")
    public List<CrossingViews.EventRow> recent(
            @RequestParam(required = false) @Pattern(regexp = "^CAM-\\d{2,}$") String camera,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return crossings.recent(camera, limit);
    }
}
