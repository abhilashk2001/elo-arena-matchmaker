package com.eloarena.dashboard;

import java.time.Instant;

/**
 * A detected double-match: a player found in two IN_PROGRESS matches at once. The dashboard uses
 * matchId and conflictingMatchId to flash both offending rows red in the match feed.
 */
public record DashboardAnomaly(
        long id,
        long playerId,
        long matchId,
        long conflictingMatchId,
        Instant detectedAt) {
}
