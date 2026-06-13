package com.eloarena.api;

import java.util.List;

/**
 * Result of a completed match. Returned both when a result is first applied and when a
 * duplicate submission is replayed, so the same call always yields the same body.
 */
public record MatchResultResponse(
        Long matchId,
        Long winnerId,
        String status,
        List<RatingChange> ratingChanges) {

    public record RatingChange(Long playerId, int ratingBefore, int ratingAfter) {
    }
}
