package com.eloarena.api;

import com.eloarena.player.PlayerSeeder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AdminController(PlayerSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(
            @RequestParam(defaultValue = "1000") @Min(1) @Max(200_000) int count,
            @RequestParam(defaultValue = "42") long seed) {
        int inserted = seeder.seed(count, seed);
        return Map.of("seeded", inserted, "seed", seed);
    }
}
