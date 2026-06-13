package com.eloarena.api;

/**
 * A player's profile, including their derived division and current live-season rank
 * (rank is null if they are not yet on the leaderboard).
 */
public record PlayerProfileResponse(
        Long id,
        String handle,
        int rating,
        int division,
        String region,
        int gamesPlayed,
        Long rank) {
}
