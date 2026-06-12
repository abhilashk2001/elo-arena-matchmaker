package com.eloarena.matchmaking;

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
 * A player's entry in the matchmaking queue. Maps to the queue_entries table.
 *
 * rating_at_join is a denormalized snapshot of the player's rating at the moment they
 * queued, so the pairing query never has to join back to players on the hot path. The
 * player is held as a plain id (not a JPA association) for the same reason.
 */
@Entity
@Table(name = "queue_entries")
public class QueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "rating_at_join", nullable = false)
    private int ratingAtJoin;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueStatus status;

    @Column(name = "matched_match_id")
    private Long matchedMatchId;

    protected QueueEntry() {
        // Required by JPA.
    }

    public QueueEntry(long playerId, int ratingAtJoin) {
        this.playerId = playerId;
        this.ratingAtJoin = ratingAtJoin;
        this.enqueuedAt = Instant.now();
        this.status = QueueStatus.WAITING;
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public int getRatingAtJoin() {
        return ratingAtJoin;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public QueueStatus getStatus() {
        return status;
    }

    public Long getMatchedMatchId() {
        return matchedMatchId;
    }
}
