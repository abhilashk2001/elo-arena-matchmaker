package com.eloarena.match;

import com.eloarena.IntegrationTest;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.rating.RatingHistory;
import com.eloarena.rating.RatingHistoryRepository;
import com.eloarena.season.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultConcurrencyTest extends IntegrationTest {

    @Autowired
    private ResultService resultService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private RatingHistoryRepository history;

    @Autowired
    private SeasonService seasons;

    @Autowired
    private JdbcTemplate jdbc;

    private long playerA;
    private long playerB;
    private long matchId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
        playerA = players.save(new Player("Alice_1500", 1500, "NA")).getId();
        playerB = players.save(new Player("Bob_1500", 1500, "NA")).getId();
        matchId = matches.save(new Match(seasons.currentSeasonId(), playerA, playerB, 1500, 1500, 0, 0, 0)).getId();
    }

    @Test
    void twoSimultaneousSubmissionsApplyTheResultExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier gate = new CyclicBarrier(2);
        try {
            Future<?> first = pool.submit(() -> submitAtGate(gate));
            Future<?> second = pool.submit(() -> submitAtGate(gate));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // The match-row lock serializes the two: one applies, the other replays.
        assertThat(players.findById(playerA).orElseThrow().getRating()).isEqualTo(1516);
        assertThat(players.findById(playerA).orElseThrow().getGamesPlayed()).isEqualTo(1);
        assertThat(history.findByMatchId(matchId)).hasSize(2);
    }

    @Test
    void aFailureMidTransactionRollsBackEverything() {
        // Pre-insert a rating row for player A and this match, so the service's own insert for
        // player A violates uq_rating_once_per_match and the whole transaction must roll back.
        history.saveAndFlush(new RatingHistory(playerA, matchId, seasons.currentSeasonId(), 1500, 1516));

        assertThatThrownBy(() -> resultService.submit(matchId, playerA))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Nothing else changed: ratings untouched, match still in progress, only the pre-inserted row.
        assertThat(players.findById(playerA).orElseThrow().getRating()).isEqualTo(1500);
        assertThat(players.findById(playerB).orElseThrow().getRating()).isEqualTo(1500);
        assertThat(matches.findById(matchId).orElseThrow().getStatus()).isEqualTo(MatchStatus.IN_PROGRESS);
        assertThat(history.findByMatchId(matchId)).hasSize(1);
    }

    private void submitAtGate(CyclicBarrier gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            resultService.submit(matchId, playerA);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
