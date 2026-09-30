package com.clipai.api.analytics;

import com.clipai.application.analytics.ControlCenterAnalytics;
import com.clipai.application.analytics.ControlCenterAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
@Tag(name = "Analytics", description = "Operational processing and evaluation summaries")
public class AnalyticsController {
    private final ControlCenterAnalyticsService analytics;

    public AnalyticsController(ControlCenterAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get Control Center dashboard metrics")
    public ControlCenterAnalytics dashboard() {
        return analytics.get();
    }
}
