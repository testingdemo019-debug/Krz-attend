package com.krztechnologies.attend.system.web;

import com.krztechnologies.attend.common.security.SignedIn;
import com.krztechnologies.attend.system.service.DashboardService;
import com.krztechnologies.attend.system.service.DashboardViews;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Dashboard, with the sections the user may see. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @Operation(
            summary = "KPIs, alerts, camera card, crossings by hour, recent crossings, review summary, enrolment"
                    + " queue, system health — each section null without its permission")
    @SignedIn
    @GetMapping
    public DashboardViews.Dashboard dashboard() {
        return dashboard.dashboard();
    }
}
