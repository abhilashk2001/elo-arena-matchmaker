package com.eloarena.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Frozen final standings for an ended season. Written once at season end (Phase 6 rollover) and
 * read back for historical leaderboards, so ended seasons never depend on Redis.
 */
@Entity
@Table(name = "season_leaderboard_snapshots")
public class SeasonLeaderboardSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "final_rating", nullable = false)
    private int finalRating;

    @Column(name = "final_rank", nullable = false)
    private int finalRank;

    @Column(name = "games_played", nullable = false)
    private int gamesPlayed;

    @Column(name = "snapshot_at", insertable = false, updatable = false)
    private Instant snapshotAt;

    protected SeasonLeaderboardSnapshot() {
        // Required by JPA.
    }

    public SeasonLeaderboardSnapshot(long seasonId, long playerId, int finalRating, int finalRank, int gamesPlayed) {
        this.seasonId = seasonId;
        this.playerId = playerId;
        this.finalRating = finalRating;
        this.finalRank = finalRank;
        this.gamesPlayed = gamesPlayed;
    }

    public Long getId() {
        return id;
    }

    public Long getSeasonId() {
        return seasonId;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public int getFinalRating() {
        return finalRating;
    }

    public int getFinalRank() {
        return finalRank;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }
}
