package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class LockingMatcherTest extends IntegrationTest {

    @Autowired
    private LockingMatcher lockingMatcher;

    @Autowired
    private QueueService queueService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
    }

    private void joinNewPlayer(String handle, int rating) {
        Player player = players.save(new Player(handle, rating, "NA"));
        queueService.join(player.getId());
    }

    @Test
    void pairsTwoCompatiblePlayers() {
        joinNewPlayer("A_1200", 1200);
        joinNewPlayer("B_1230", 1230);

        int created = lockingMatcher.matchTick();

        assertThat(created).isEqualTo(1);
        assertThat(matches.count()).isEqualTo(1);
        assertThat(waitingCount()).isZero();
    }

    @Test
    void leavesClaimedButUnpairedPlayerWaiting() {
        // Three compatible players: one pair forms, the odd one out stays WAITING and is
        // claimable again next tick.
        joinNewPlayer("A_1500", 1500);
        joinNewPlayer("B_1500", 1500);
        joinNewPlayer("C_1500", 1500);

        int created = lockingMatcher.matchTick();

        assertThat(created).isEqualTo(1);
        assertThat(matches.count()).isEqualTo(1);
        assertThat(waitingCount()).isEqualTo(1);
    }

    @Test
    void leavesIncompatiblePlayersWaiting() {
        joinNewPlayer("Low_1200", 1200);
        joinNewPlayer("High_1330", 1330);

        int created = lockingMatcher.matchTick();

        assertThat(created).isZero();
        assertThat(matches.count()).isZero();
        assertThat(waitingCount()).isEqualTo(2);
    }

    private Long waitingCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM queue_entries WHERE status = 'WAITING'", Long.class);
    }
}
