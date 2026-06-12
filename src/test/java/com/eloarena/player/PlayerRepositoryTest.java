package com.eloarena.player;

import com.eloarena.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Player reads and writes against a real Postgres container.
 * Reaching the database is the point: the entity mapping is validated against the
 * Flyway schema at startup, and the round trip proves persistence works.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlayerRepositoryTest {

    @Autowired
    private PlayerRepository players;

    @Test
    void savesAndReadsBackAPlayer() {
        Player saved = players.save(new Player("CrimsonFox_4821", 1500, "NA"));

        assertThat(saved.getId()).isNotNull();

        Optional<Player> found = players.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getHandle()).isEqualTo("CrimsonFox_4821");
        assertThat(found.get().getRating()).isEqualTo(1500);
        assertThat(found.get().getRegion()).isEqualTo("NA");
        assertThat(found.get().getGamesPlayed()).isZero();
    }

    @Test
    void looksUpByHandle() {
        players.save(new Player("IronWolf_1009", 1320, "EU"));

        assertThat(players.findByHandle("IronWolf_1009")).isPresent();
        assertThat(players.existsByHandle("IronWolf_1009")).isTrue();
        assertThat(players.existsByHandle("NobodyHere_0000")).isFalse();
    }
}
