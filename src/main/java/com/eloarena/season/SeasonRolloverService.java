package com.eloarena.season;

import com.eloarena.leaderboard.Leaderboard;
import com.eloarena.leaderboard.LeaderboardRebuildService;
import com.eloarena.rating.Divisions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ends the active season and opens the next one. The whole database side runs in a single
 * transaction so the world never appears half-rolled-over: either the old season is frozen,
 * ratings are reset, and a new season is open, or nothing changed.
 *
 * Steps, in order:
 *   1. Freeze the final standings of the ending season into season_leaderboard_snapshots,
 *      ranking players by rating (ties broken by id) with ROW_NUMBER(). This is computed in
 *      the database in one statement so the ranks are a consistent view of the same instant.
 *   2. Close the season (stamp ended_at).
 *   3. Soft reset: every player drops one division (-{@link Divisions#WIDTH}), clamped at the
 *      {@link Divisions#FLOOR}. eFootball-style soft landing, not a wipe back to 1200.
 *   4. Open the next season.
 *
 * Redis is a rebuildable projection, not part of the transaction. The live board is reset only
 * AFTER the database transaction commits: the ended season's key is dropped and the new season's
 * board is rebuilt from the freshly reset ratings. If we did this inside the transaction and it
 * rolled back, Redis would be left lying about a rollover that never happened.
 */
@Service
public class SeasonRolloverService {

    private final JdbcTemplate jdbc;
    private final SeasonRepository seasons;
    private final StringRedisTemplate redis;
    private final LeaderboardRebuildService leaderboardRebuild;

    public SeasonRolloverService(JdbcTemplate jdbc,
                                 SeasonRepository seasons,
                                 StringRedisTemplate redis,
                                 LeaderboardRebuildService leaderboardRebuild) {
        this.jdbc = jdbc;
        this.seasons = seasons;
        this.redis = redis;
        this.leaderboardRebuild = leaderboardRebuild;
    }

    @Transactional
    public RolloverResult rollover() {
        Season ending = seasons.findFirstByEndedAtIsNull()
                .orElseThrow(() -> new IllegalStateException("No active season to roll over"));
        long endedSeasonId = ending.getId();

        // 1. Freeze final standings. ROW_NUMBER() gives dense 1-based ranks over a single ordering.
        int snapshotted = jdbc.update("""
                INSERT INTO season_leaderboard_snapshots (season_id, player_id, final_rating, final_rank, games_played)
                SELECT ?, id, rating, ROW_NUMBER() OVER (ORDER BY rating DESC, id ASC), games_played
                FROM players
                """, endedSeasonId);

        // 2. Close the ending season. Freeing the partial unique index lets a new active season open.
        jdbc.update("UPDATE seasons SET ended_at = now() WHERE id = ?", endedSeasonId);

        // 3. Soft reset every player's rating: drop one division, clamped to the floor.
        jdbc.update("UPDATE players SET rating = GREATEST(rating - ?, ?)", Divisions.WIDTH, Divisions.FLOOR);

        // 4. Open the next season.
        long nextNumber = seasons.count() + 1;
        String newName = "Season " + nextNumber;
        Long newSeasonId = jdbc.queryForObject(
                "INSERT INTO seasons (name) VALUES (?) RETURNING id", Long.class, newName);

        // 5. Reset the live board only after the database transaction safely commits.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.delete(Leaderboard.key(endedSeasonId));
                leaderboardRebuild.rebuildActiveSeason();
            }
        });

        return new RolloverResult(endedSeasonId, ending.getName(), snapshotted, newSeasonId, newName);
    }

    /** Summary of a completed rollover, for the admin response. */
    public record RolloverResult(long endedSeasonId, String endedSeasonName, int playersSnapshotted,
                                 long newSeasonId, String newSeasonName) {
    }
}
