package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import com.eloarena.match.Match;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaiveMatcherTest extends IntegrationTest {

    @Autowired
    private NaiveMatcher naiveMatcher;

    @Autowired
    private QueueService queueService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private QueueEntryRepository queue;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
    }

    private long joinNewPlayer(String handle, int rating) {
        Player player = players.save(new Player(handle, rating, "NA"));
        queueService.join(player.getId());
        return player.getId();
    }

    @Test
    void pairsTwoCompatiblePlayers() {
        long a = joinNewPlayer("A_1200", 1200);
        long b = joinNewPlayer("B_1230", 1230);

        int created = naiveMatcher.matchTick();

        assertThat(created).isEqualTo(1);
        assertThat(matches.count()).isEqualTo(1);
        assertThat(waitingCount()).isZero();

        Match match = matches.findAll().get(0);
        assertThat(List.of(match.getPlayerAId(), match.getPlayerBId())).containsExactlyInAnyOrder(a, b);
        assertThat(match.getRatingDelta()).isEqualTo(30);
        assertThat(match.getWaitMsA()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void leavesIncompatiblePlayersWaiting() {
        // Just joined, so each band is 50. A delta of 130 is too far to pair yet.
        joinNewPlayer("Low_1200", 1200);
        joinNewPlayer("High_1330", 1330);

        int created = naiveMatcher.matchTick();

        assertThat(created).isZero();
        assertThat(matches.count()).isZero();
        assertThat(waitingCount()).isEqualTo(2);
    }

    @Test
    void pairsMultiplePairsInOneTick() {
        joinNewPlayer("A_1200", 1200);
        joinNewPlayer("B_1210", 1210);
        joinNewPlayer("C_1800", 1800);
        joinNewPlayer("D_1820", 1820);

        int created = naiveMatcher.matchTick();

        assertThat(created).isEqualTo(2);
        assertThat(matches.count()).isEqualTo(2);
        assertThat(waitingCount()).isZero();
    }

    private Long waitingCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM queue_entries WHERE status = 'WAITING'", Long.class);
    }
}
