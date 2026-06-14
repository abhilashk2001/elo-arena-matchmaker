package com.eloarena.api;

import com.eloarena.leaderboard.LeaderboardRebuildService;
import com.eloarena.matchmaking.StrategySelector;
import com.eloarena.player.PlayerSeeder;
import com.eloarena.season.SeasonRolloverService;
import com.eloarena.season.SeasonRolloverService.RolloverResult;
import com.eloarena.simulation.SimulationConfig;
import com.eloarena.simulation.SimulationStatus;
import com.eloarena.simulation.Simulator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Administrative and simulation-control endpoints. Seeding lives here for now;
 * later phases add simulation start/stop, the matcher-strategy toggle, season
 * rollover, and the leaderboard rebuild.
 */
@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminController {

    private final PlayerSeeder seeder;
    private final StrategySelector strategies;
    private final LeaderboardRebuildService leaderboardRebuild;
    private final SeasonRolloverService seasonRollover;
    private final Simulator simulator;

    public AdminController(PlayerSeeder seeder,
                          StrategySelector strategies,
                          LeaderboardRebuildService leaderboardRebuild,
                          SeasonRolloverService seasonRollover,
                          Simulator simulator) {
        this.seeder = seeder;
        this.strategies = strategies;
        this.leaderboardRebuild = leaderboardRebuild;
        this.seasonRollover = seasonRollover;
        this.simulator = simulator;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(
            @RequestParam(defaultValue = "1000") @Min(1) @Max(200_000) int count,
            @RequestParam(defaultValue = "42") long seed) {
        int inserted = seeder.seed(count, seed);
        return Map.of("seeded", inserted, "seed", seed);
    }

    @PutMapping("/matcher-strategy")
    public Map<String, Object> setStrategy(@Valid @RequestBody SetStrategyRequest request) {
        strategies.select(request.strategy());
        return Map.of("strategy", strategies.currentName(), "available", strategies.available());
    }

    @PostMapping("/rebuild-leaderboard")
    public Map<String, Object> rebuildLeaderboard() {
        long rebuilt = leaderboardRebuild.rebuildActiveSeason();
        return Map.of("rebuilt", rebuilt);
    }

    @PostMapping("/end-season")
    public Map<String, Object> endSeason() {
        RolloverResult result = seasonRollover.rollover();
        return Map.of(
                "endedSeasonId", result.endedSeasonId(),
                "endedSeasonName", result.endedSeasonName(),
                "playersSnapshotted", result.playersSnapshotted(),
                "newSeasonId", result.newSeasonId(),
                "newSeasonName", result.newSeasonName());
    }

    @PostMapping("/simulate/start")
    public SimulationStatus startSimulation(@Valid @RequestBody SimulationConfig config) {
        return simulator.start(config);
    }

    @PostMapping("/simulate/stop")
    public SimulationStatus stopSimulation() {
        return simulator.stop();
    }

    @GetMapping("/simulate/status")
    public SimulationStatus simulationStatus() {
        return simulator.status();
    }
}
