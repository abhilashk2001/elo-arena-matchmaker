package com.eloarena.api;

import com.eloarena.matchmaking.QueueEntry;

import java.time.Instant;

/**
 * Response shape for a queue entry.
 */
public record QueueEntryResponse(
        Long id,
        Long playerId,
        int ratingAtJoin,
        String status,
        Long matchedMatchId,
        Instant enqueuedAt) {

    public static QueueEntryResponse from(QueueEntry entry) {
        return new QueueEntryResponse(
                entry.getId(),
                entry.getPlayerId(),
                entry.getRatingAtJoin(),
                entry.getStatus().name(),
                entry.getMatchedMatchId(),
                entry.getEnqueuedAt());
    }
}
