package com.eloarena.api;

/**
 * One ranked leaderboard row. Same shape whether it came from the live Redis leaderboard
 * (active season) or a stored snapshot (ended season).
 */
public record LeaderboardEntryResponse(long rank, long playerId, String handle, int rating) {
}
