package com.eloarena.season;

import com.eloarena.IntegrationTest;
import com.eloarena.leaderboard.Leaderboard;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonRolloverTest extends IntegrationTest {

    @Autowired
    private SeasonRolloverService rollover;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private SeasonRepository seasons;

    @Autowired
    private SeasonLeaderboardSnapshotRepository snapshots;

    @Autowired
    private SeasonService seasonService;

    @Autowired
    private Leaderboard leaderboard;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    private long topId;
    private long midId;
    private long floorId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
        // Keep exactly one active season: close any extra opened by an earlier rollover.
        jdbc.update("UPDATE seasons SET ended_at = now() WHERE ended_at IS NULL AND id <> "
                + "(SELECT min(id) FROM seasons WHERE ended_at IS NULL)");
        jdbc.update("UPDATE seasons SET ended_at = NULL WHERE id = (SELECT min(id) FROM seasons)");

        topId = players.save(new Player("Top_2400", 2400, "NA")).getId();
        midId = players.save(new Player("Mid_1500", 1500, "EU")).getId();
        floorId = players.save(new Player("Floor_450", 450, "APAC")).getId();

        long seasonId = seasonService.currentSeasonId();
        leaderboard.addRating(seasonId, topId, 2400);
        leaderboard.addRating(seasonId, midId, 1500);
        leaderboard.addRating(seasonId, floorId, 450);
    }

    @Test
    void rolloverFreezesStandingsResetsRatingsAndOpensNextSeason() {
        long endedSeasonId = seasonService.currentSeasonId();

        SeasonRolloverService.RolloverResult result = rollover.rollover();

        // The ending season is closed and a new active season is open.
        assertThat(result.endedSeasonId()).isEqualTo(endedSeasonId);
        assertThat(seasons.findById(endedSeasonId)).get()
                .extracting(Season::getEndedAt).isNotNull();
        long newSeasonId = seasonService.currentSeasonId();
        assertThat(newSeasonId).isEqualTo(result.newSeasonId());
        assertThat(newSeasonId).isNotEqualTo(endedSeasonId);

        // Final standings are frozen with ROW_NUMBER ranks (highest rating = rank 1).
        assertThat(result.playersSnapshotted()).isEqualTo(3);
        List<SeasonLeaderboardSnapshot> frozen =
                snapshots.findBySeasonIdOrderByFinalRankAsc(endedSeasonId, org.springframework.data.domain.Pageable.unpaged());
        assertThat(frozen).extracting(SeasonLeaderboardSnapshot::getPlayerId)
                .containsExactly(topId, midId, floorId);
        assertThat(frozen).extracting(SeasonLeaderboardSnapshot::getFinalRank)
                .containsExactly(1, 2, 3);
        assertThat(frozen.get(0).getFinalRating()).isEqualTo(2400);

        // Soft reset: every player drops 200, clamped at the 400 floor.
        assertThat(players.findById(topId)).get().extracting(Player::getRating).isEqualTo(2200);
        assertThat(players.findById(midId)).get().extracting(Player::getRating).isEqualTo(1300);
        assertThat(players.findById(floorId)).get().extracting(Player::getRating).isEqualTo(400);

        // The old live board is gone; the new season's board reflects the reset ratings.
        assertThat(redis.hasKey(Leaderboard.key(endedSeasonId))).isFalse();
        assertThat(leaderboard.scoreOf(newSeasonId, topId)).isEqualTo(2200.0);
        assertThat(leaderboard.scoreOf(newSeasonId, floorId)).isEqualTo(400.0);
        assertThat(leaderboard.rankOf(newSeasonId, topId)).isEqualTo(1);
    }
}
