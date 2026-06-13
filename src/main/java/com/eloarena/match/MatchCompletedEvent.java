package com.eloarena.match;

/**
 * Published when a match is completed, carrying both players' new ratings. Consumed after the
 * transaction commits to update the Redis leaderboard.
 */
public record MatchCompletedEvent(
        long seasonId,
        long playerAId, int newRatingA,
        long playerBId, int newRatingB) {
}
