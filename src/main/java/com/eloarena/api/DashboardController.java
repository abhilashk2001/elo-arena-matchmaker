package com.eloarena.api;

import com.eloarena.dashboard.DashboardAnomaly;
import com.eloarena.dashboard.DashboardMatch;
import com.eloarena.dashboard.DashboardQueueEntry;
import com.eloarena.dashboard.DashboardService;
import com.eloarena.dashboard.DashboardStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoints the ops dashboard polls. These are separate from the /api resource endpoints
 * on purpose: they are denormalised, bounded, view-shaped reads tuned for frequent polling, not the
 * canonical REST surface. Each is held to an under-50ms budget at 10k players (see DashboardService).
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/queue")
    public List<DashboardQueueEntry> queue() {
        return dashboard.queue();
    }

    @GetMapping("/matches")
    public List<DashboardMatch> matches() {
        return dashboard.matches();
    }

    @GetMapping("/stats")
    public DashboardStats stats() {
        return dashboard.stats();
    }

    @GetMapping("/anomalies")
    public List<DashboardAnomaly> anomalies() {
        return dashboard.anomalies();
    }
}
