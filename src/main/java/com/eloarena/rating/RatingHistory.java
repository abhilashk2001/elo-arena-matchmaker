package com.eloarena.rating;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only record of a single rating change from one match. Never updated. The
 * uq_rating_once_per_match constraint (player_id, match_id) makes it the database-level
 * idempotency backstop: a rating can be applied at most once per player per match.
 */
@Entity
@Table(name = "rating_history")
public class RatingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "rating_before", nullable = false)
    private int ratingBefore;

    @Column(name = "rating_after", nullable = false)
    private int ratingAfter;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected RatingHistory() {
        // Required by JPA.
    }

    public RatingHistory(long playerId, long matchId, long seasonId, int ratingBefore, int ratingAfter) {
        this.playerId = playerId;
        this.matchId = matchId;
        this.seasonId = seasonId;
        this.ratingBefore = ratingBefore;
        this.ratingAfter = ratingAfter;
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public Long getMatchId() {
        return matchId;
    }

    public Long getSeasonId() {
        return seasonId;
    }

    public int getRatingBefore() {
        return ratingBefore;
    }

    public int getRatingAfter() {
        return ratingAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
