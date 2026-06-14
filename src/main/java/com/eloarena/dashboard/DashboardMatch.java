package com.eloarena.dashboard;

import java.time.Instant;

/**
 * One row in the match feed. ratingDelta and the wait times are the band-quality signals the panel
 * shows; the frontend flashes a row red when its id appears in the anomalies feed.
 */
public record DashboardMatch(
        long id,
        long playerAId, String handleA, int ratingA,
        long playerBId, String handleB, int ratingB,
        int ratingDelta,
        long waitMsA, long waitMsB,
        Instant createdAt) {
}
