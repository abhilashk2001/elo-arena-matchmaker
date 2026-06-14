package com.eloarena.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds players once on first boot so that `docker compose up` on a clean machine yields a working,
 * populated system with no extra steps (the §11 acceptance bar). Off by default; the compose app
 * service turns it on with ELOARENA_SEED_ON_BOOT_ENABLED=true.
 *
 * It is idempotent across restarts: it seeds only when the players table is empty, so a restart
 * against the persistent Postgres volume does not re-seed or duplicate. Manual seeding via
 * POST /api/admin/seed is unaffected.
 */
@Component
@ConditionalOnProperty(name = "eloarena.seed-on-boot.enabled", havingValue = "true")
public class SeedOnBoot implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedOnBoot.class);

    private final PlayerRepository players;
    private final PlayerSeeder seeder;
    private final int count;
    private final long seed;

    public SeedOnBoot(PlayerRepository players,
                      PlayerSeeder seeder,
                      @Value("${eloarena.seed-on-boot.count:1000}") int count,
                      @Value("${eloarena.seed-on-boot.seed:42}") long seed) {
        this.players = players;
        this.seeder = seeder;
        this.count = count;
        this.seed = seed;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        long existing = players.count();
        if (existing > 0) {
            log.info("Seed-on-boot skipped: {} players already present", existing);
            return;
        }
        int inserted = seeder.seed(count, seed);
        log.info("Seed-on-boot inserted {} players (seed={})", inserted, seed);
    }
}
