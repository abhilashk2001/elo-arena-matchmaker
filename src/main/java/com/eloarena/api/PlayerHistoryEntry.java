package com.eloarena.api;

import java.time.Instant;

/**
 * One rating change in a player's history.
 */
public record PlayerHistoryEntry(Long matchId, int ratingBefore, int ratingAfter, Instant createdAt) {
}
