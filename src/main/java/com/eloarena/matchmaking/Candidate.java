package com.eloarena.matchmaking;

import java.time.Instant;

/**
 * A waiting queue entry considered for pairing. rating is the snapshot taken at join
 * time (rating_at_join), and enqueuedAt is used to compute how long the player has waited.
 */
public record Candidate(long queueEntryId, long playerId, int rating, Instant enqueuedAt) {
}
