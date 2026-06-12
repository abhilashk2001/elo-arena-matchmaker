package com.eloarena.player;

import com.eloarena.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlayerSeederTest {

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

    private List<String> sortedHandles() {
        return players.findAll().stream().map(Player::getHandle).sorted().toList();
    }
}
