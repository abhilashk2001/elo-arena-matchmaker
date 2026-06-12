package com.eloarena.player;

import com.eloarena.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerSeederTest extends IntegrationTest {

    @Autowired
    private PlayerSeeder seeder;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
    }

    @Test
    void seedsRequestedCountWithUniqueHandlesAndRatingsInRange() {
        int inserted = seeder.seed(2_000, 42L);

        assertThat(inserted).isEqualTo(2_000);
        assertThat(players.count()).isEqualTo(2_000);

        List<Player> all = players.findAll();
        assertThat(all).allSatisfy(p -> assertThat(p.getRating()).isBetween(400, 3000));

        long distinctHandles = all.stream().map(Player::getHandle).distinct().count();
        assertThat(distinctHandles).isEqualTo(2_000);
    }

    @Test
    void isDeterministicForAGivenSeed() {
        seeder.seed(300, 7L);
        List<String> firstRun = sortedHandles();

        jdbc.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
        seeder.seed(300, 7L);
        List<String> secondRun = sortedHandles();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    @Test
    void ratingDistributionIsCenteredNear1200() {
        seeder.seed(5_000, 99L);

        List<Player> all = players.findAll();
        double mean = all.stream().mapToInt(Player::getRating).average().orElseThrow();
        int min = all.stream().mapToInt(Player::getRating).min().orElseThrow();
        int max = all.stream().mapToInt(Player::getRating).max().orElseThrow();

        // Tolerant check: a normal sample around 1200 should land close to 1200.
        assertThat(mean).isBetween(1170.0, 1230.0);
        // Clamping must hold the tails inside [400, 3000].
        assertThat(min).isGreaterThanOrEqualTo(400);
        assertThat(max).isLessThanOrEqualTo(3000);
    }

    private List<String> sortedHandles() {
        return players.findAll().stream().map(Player::getHandle).sorted().toList();
    }
}
