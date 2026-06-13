package com.eloarena.api;

/**
 * A player's profile, including their current live-season rank (null if not yet on the
 * leaderboard).
 */
public record PlayerProfileResponse(
        Long id,
        String handle,
        int rating,
        String region,
        int gamesPlayed,
        Long rank) {
}
