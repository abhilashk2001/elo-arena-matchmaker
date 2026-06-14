package com.eloarena.dashboard;

/**
 * The stats-strip snapshot. Cheap to produce: queueDepth and matchesPerSec come from Redis,
 * avgWaitMs and p99PairingLatencyMs from in-memory Micrometer timers, currentStrategy from the
 * live selector, and only anomalyCount touches Postgres.
 *
 * @param queueDepth           players currently waiting
 * @param matchesPerSec        matches created per second, averaged over a short sliding window
 * @param avgWaitMs            mean queue wait of matched players, in milliseconds
 * @param p99PairingLatencyMs  99th-percentile pairing-pass duration, in milliseconds
 * @param activeMatcherCount   matchers currently heartbeating across all instances
 * @param currentStrategy      "locking" or "naive"
 * @param anomalyCount         players currently double-booked (recent window); the headline number,
 *                             reads zero under locking and climbs under naive
 */
public record DashboardStats(
        long queueDepth,
        double matchesPerSec,
        double avgWaitMs,
        double p99PairingLatencyMs,
        int activeMatcherCount,
        String currentStrategy,
        long anomalyCount) {
}
