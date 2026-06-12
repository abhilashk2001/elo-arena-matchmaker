package com.eloarena.player;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates synthetic players for development and benchmarks.
 *
 * Ratings are drawn from a normal distribution (mean 1200, stddev 300) clamped to
 * [400, 3000]. The bell curve is deliberate: mid-rated players match instantly in a
 * dense neighbourhood while rare tail players must wait for their band to expand,
 * which is the behaviour the matcher exists to handle. A uniform distribution would
 * hide that.
 *
 * Generation is deterministic for a given seed, so a benchmark can be reproduced
 * exactly. Inserts use JdbcTemplate batch updates because GenerationType.IDENTITY
 * prevents Hibernate from batching inserts, and seeding tens of thousands of rows
 * one statement at a time would be needlessly slow.
 */
@Service
public class PlayerSeeder {

    private static final int RATING_MEAN = 1200;
    private static final int RATING_STDDEV = 300;
    private static final int RATING_MIN = 400;
    private static final int RATING_MAX = 3000;
    private static final int BATCH_SIZE = 1000;

    private static final String[] ADJECTIVES = {
            "Crimson", "Iron", "Silent", "Golden", "Shadow", "Frost", "Blazing", "Lunar",
            "Savage", "Mystic", "Rapid", "Vivid", "Stoic", "Feral", "Noble", "Rogue",
            "Cobalt", "Amber", "Hollow", "Radiant"
    };
    private static final String[] NOUNS = {
            "Fox", "Wolf", "Hawk", "Viper", "Titan", "Raven", "Lynx", "Drake",
            "Falcon", "Bear", "Cobra", "Phoenix", "Jaguar", "Otter", "Bison", "Heron",
            "Mantis", "Stag", "Orca", "Kite"
    };
    private static final String[] REGIONS = {"NA", "EU", "APAC"};

    private final JdbcTemplate jdbc;

    public PlayerSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Generate and insert {@code count} players using {@code seed} for reproducibility.
     * Returns the number of players inserted.
     */
    public int seed(int count, long seed) {
        Random rng = new Random(seed);
        Set<String> handles = new HashSet<>(count * 2);
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int inserted = 0;

        for (int i = 0; i < count; i++) {
            String handle = uniqueHandle(rng, handles);
            int rating = clampedGaussianRating(rng);
            String region = REGIONS[rng.nextInt(REGIONS.length)];
            batch.add(new Object[]{handle, rating, region});

            if (batch.size() == BATCH_SIZE) {
                flush(batch);
                inserted += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            flush(batch);
            inserted += batch.size();
        }
        return inserted;
    }

    private void flush(List<Object[]> batch) {
        jdbc.batchUpdate("INSERT INTO players (handle, rating, region) VALUES (?, ?, ?)", batch);
    }

    private String uniqueHandle(Random rng, Set<String> seen) {
        // Adjective + Noun + 4-digit number, e.g. "CrimsonFox_4821".
        // Retry on the rare in-batch collision so handles stay unique and deterministic.
        for (int attempt = 0; attempt < 1000; attempt++) {
            String adjective = ADJECTIVES[rng.nextInt(ADJECTIVES.length)];
            String noun = NOUNS[rng.nextInt(NOUNS.length)];
            int number = 1000 + rng.nextInt(9000);
            String handle = adjective + noun + "_" + number;
            if (seen.add(handle)) {
                return handle;
            }
        }
        throw new IllegalStateException("Exhausted attempts generating a unique handle");
    }

    private int clampedGaussianRating(Random rng) {
        long rating = Math.round(rng.nextGaussian() * RATING_STDDEV + RATING_MEAN);
        return (int) Math.max(RATING_MIN, Math.min(RATING_MAX, rating));
    }
}
