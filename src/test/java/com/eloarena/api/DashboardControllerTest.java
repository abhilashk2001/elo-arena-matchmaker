package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.dashboard.DashboardService;
import com.eloarena.match.Match;
import com.eloarena.match.MatchRepository;
import com.eloarena.matchmaking.AnomalyDetector;
import com.eloarena.matchmaking.QueueEntry;
import com.eloarena.matchmaking.QueueEntryRepository;
import com.eloarena.matchmaking.RedisLiveStats;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.season.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shape and latency checks for the dashboard read endpoints. The strict under-50ms-at-10k-players
 * budget is verified separately by scripts/dashboard-latency-benchmark.sh against a loaded system;
 * here we assert the contract (correct fields) and a sanity latency bound on representative data so
 * a regression that turns a bounded read into a full scan is caught in the suite.
 */
class DashboardControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private QueueEntryRepository queue;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private AnomalyDetector anomalyDetector;

    @Autowired
    private RedisLiveStats liveStats;

    @Autowired
    private SeasonService seasons;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
    }

    @Test
    void queueReturnsWaitingPlayersWithServerComputedBand() throws Exception {
        long alice = players.save(new Player("Alice_1500", 1500, "NA")).getId();
        queue.save(new QueueEntry(alice, 1500));

        mockMvc.perform(get("/dashboard/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].playerId").value(alice))
                .andExpect(jsonPath("$[0].handle").value("Alice_1500"))
                .andExpect(jsonPath("$[0].rating").value(1500))
                .andExpect(jsonPath("$[0].waitMs").isNumber())
                // Fresh entry, so the band is the configured base (50) and is present.
                .andExpect(jsonPath("$[0].currentBand").value(50));
    }

    @Test
    void matchesReturnsRecentMatchesNewestFirst() throws Exception {
        long a = players.save(new Player("A_1500", 1500, "NA")).getId();
        long b = players.save(new Player("B_1480", 1480, "NA")).getId();
        long season = seasons.currentSeasonId();
        matches.save(new Match(season, a, b, 1500, 1480, 20, 100, 200));
        long newer = matches.save(new Match(season, a, b, 1500, 1480, 20, 50, 75)).getId();

        mockMvc.perform(get("/dashboard/matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[0].handleA").value("A_1500"))
                .andExpect(jsonPath("$[0].handleB").value("B_1480"))
                .andExpect(jsonPath("$[0].ratingDelta").value(20))
                .andExpect(jsonPath("$[0].waitMsA").value(50));
    }

    @Test
    void statsExposesEveryHealthField() throws Exception {
        liveStats.setQueueDepth(7);

        mockMvc.perform(get("/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueDepth").value(7))
                .andExpect(jsonPath("$.matchesPerSec").isNumber())
                .andExpect(jsonPath("$.avgWaitMs").isNumber())
                .andExpect(jsonPath("$.p99PairingLatencyMs").isNumber())
                .andExpect(jsonPath("$.activeMatcherCount").isNumber())
                // Default strategy is locking; the demo's headline anomaly counter reads zero there.
                .andExpect(jsonPath("$.currentStrategy").value("locking"))
                .andExpect(jsonPath("$.anomalyCount").value(0));
    }

    @Test
    void anomaliesReturnsDetectedDoubleMatches() throws Exception {
        long x = players.save(new Player("X_1500", 1500, "NA")).getId();
        long y = players.save(new Player("Y_1500", 1500, "NA")).getId();
        long z = players.save(new Player("Z_1500", 1500, "NA")).getId();
        long season = seasons.currentSeasonId();
        matches.save(new Match(season, x, y, 1500, 1500, 0, 0, 0));
        long second = matches.save(new Match(season, x, z, 1500, 1500, 0, 0, 0)).getId();
        anomalyDetector.check(second, x, z);

        mockMvc.perform(get("/dashboard/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].playerId").value(x))
                .andExpect(jsonPath("$[0].matchId").value(second))
                .andExpect(jsonPath("$[0].detectedAt").exists());
    }

    @Test
    void readsStayWellWithinBudgetOnRepresentativeData() {
        // Representative working set: a screenful of waiting players and a backlog of matches the
        // feed reads the tail of. The endpoints are bounded top-N reads, so latency must not scale
        // with this data; we warm up, then assert the median read is comfortably under budget.
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(players.save(new Player("P" + i + "_1500", 1500 + i, "NA")).getId());
        }
        long season = seasons.currentSeasonId();
        for (int i = 0; i < 100; i++) {
            queue.save(new QueueEntry(ids.get(i), 1500 + i));
        }
        for (int i = 0; i < 500; i++) {
            long a = ids.get(i % ids.size());
            long b = ids.get((i + 1) % ids.size());
            matches.save(new Match(season, a, b, 1500, 1490, 10, 100, 120));
        }

        // Warm up the JIT and the connection pool so the measurement reflects steady state.
        for (int i = 0; i < 10; i++) {
            dashboard.queue();
            dashboard.matches();
            dashboard.stats();
            dashboard.anomalies();
        }

        assertThat(medianMillis(dashboard::queue)).isLessThan(50);
        assertThat(medianMillis(dashboard::matches)).isLessThan(50);
        assertThat(medianMillis(dashboard::stats)).isLessThan(50);
        assertThat(medianMillis(dashboard::anomalies)).isLessThan(50);
    }

    private long medianMillis(Runnable read) {
        long[] samples = new long[21];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            read.run();
            samples[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
