package com.eloarena.matchmaking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A recorded double-match: a player found in more than one IN_PROGRESS match.
 * Maps to the anomalies table.
 */
@Entity
@Table(name = "anomalies")
public class Anomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "conflicting_match_id", nullable = false)
    private Long conflictingMatchId;

    @Column(name = "detected_at", insertable = false, updatable = false)
    private Instant detectedAt;

    protected Anomaly() {
        // Required by JPA.
    }

    public Anomaly(long playerId, long matchId, long conflictingMatchId) {
        this.playerId = playerId;
        this.matchId = matchId;
        this.conflictingMatchId = conflictingMatchId;
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

    public Long getConflictingMatchId() {
        return conflictingMatchId;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
