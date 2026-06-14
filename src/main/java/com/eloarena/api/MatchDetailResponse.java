package com.eloarena.api;

import com.eloarena.match.Match;

import java.time.Instant;

/**
 * Read view of a match. The simulator reads this after being matched to learn its opponent's
 * rating and pick a rating-aware winner; the dashboard uses it to show match details.
 */
public record MatchDetailResponse(
        Long id,
        Long seasonId,
        Long playerAId,
        Long playerBId,
        int ratingA,
        int ratingB,
        int ratingDelta,
        String status,
        Long winnerId,
        Instant createdAt,
        Instant completedAt) {

    public static MatchDetailResponse from(Match match) {
        return new MatchDetailResponse(
                match.getId(),
                match.getSeasonId(),
                match.getPlayerAId(),
                match.getPlayerBId(),
                match.getRatingA(),
                match.getRatingB(),
                match.getRatingDelta(),
                match.getStatus().name(),
                match.getWinnerId(),
                match.getCreatedAt(),
                match.getCompletedAt());
    }
}
