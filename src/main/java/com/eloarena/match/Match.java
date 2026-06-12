package com.eloarena.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A match between two players. Created IN_PROGRESS by the matcher; completed with a winner
 * by the result endpoint in Phase 5. Ratings and wait times are captured at creation as
 * quality metrics (rating_delta and wait_ms are how the dashboard shows band quality and
 * how long players waited).
 */
@Entity
@Table(name = "matches")
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "player_a_id", nullable = false)
    private Long playerAId;

    @Column(name = "player_b_id", nullable = false)
    private Long playerBId;

    @Column(name = "rating_a", nullable = false)
    private int ratingA;

    @Column(name = "rating_b", nullable = false)
    private int ratingB;

    @Column(name = "rating_delta", nullable = false)
    private int ratingDelta;

    @Column(name = "wait_ms_a", nullable = false)
    private long waitMsA;

    @Column(name = "wait_ms_b", nullable = false)
    private long waitMsB;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus status;

    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Match() {
        // Required by JPA.
    }

    public Match(long seasonId, long playerAId, long playerBId,
                 int ratingA, int ratingB, int ratingDelta,
                 long waitMsA, long waitMsB) {
        this.seasonId = seasonId;
        this.playerAId = playerAId;
        this.playerBId = playerBId;
        this.ratingA = ratingA;
        this.ratingB = ratingB;
        this.ratingDelta = ratingDelta;
        this.waitMsA = waitMsA;
        this.waitMsB = waitMsB;
        this.status = MatchStatus.IN_PROGRESS;
    }

    public Long getId() {
        return id;
    }

    public Long getSeasonId() {
        return seasonId;
    }

    public Long getPlayerAId() {
        return playerAId;
    }

    public Long getPlayerBId() {
        return playerBId;
    }

    public int getRatingA() {
        return ratingA;
    }

    public int getRatingB() {
        return ratingB;
    }

    public int getRatingDelta() {
        return ratingDelta;
    }

    public long getWaitMsA() {
        return waitMsA;
    }

    public long getWaitMsB() {
        return waitMsB;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public Long getWinnerId() {
        return winnerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
