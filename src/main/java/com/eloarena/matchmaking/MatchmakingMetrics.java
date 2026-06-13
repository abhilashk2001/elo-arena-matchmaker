package com.eloarena.matchmaking;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The matchmaking metrics the dashboard and the benchmark write-ups read. Counters and timers are
 * registered once and reused; tags (like the matcher strategy) let one series answer "how many
 * matches did locking vs naive create" without separate meters.
 *
 * These are the numbers that tell the performance story: queue.wait shows how long players sit
 * under load, pairing.duration shows the cost of the matching algorithm itself, and matches.created
 * by strategy is the throughput the scaling analysis compares across matcher counts.
 */
@Component
public class MatchmakingMetrics {

    private final MeterRegistry registry;
    private final Timer queueWaitTimer;
    private final Timer pairingTimer;

    public MatchmakingMetrics(MeterRegistry registry, RedisLiveStats liveStats) {
        this.registry = registry;
        this.queueWaitTimer = Timer.builder("eloarena.queue.wait")
                .description("How long a player waited in the queue before being matched")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.pairingTimer = Timer.builder("eloarena.pairing.duration")
                .description("Time spent in the greedy pairing algorithm per matcher tick")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        // A gauge polls its source on read. We bind it to the live queue depth the matcher keeps
        // in Redis, so scraping the gauge costs a Redis GET, not a Postgres count.
        Gauge.builder("eloarena.queue.depth", liveStats, RedisLiveStats::queueDepth)
                .description("Number of players currently waiting in the queue")
                .register(registry);
    }

    /** Count matches created this tick, tagged by the strategy that created them. */
    public void matchesCreated(String strategy, int count) {
        if (count > 0) {
            registry.counter("eloarena.matches.created", "strategy", strategy).increment(count);
        }
    }

    /** Record how long a just-matched player waited in the queue. */
    public void recordQueueWait(long millis) {
        queueWaitTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    /** Time a pairing pass and return its result. */
    public <T> T timePairing(Supplier<T> pairing) {
        return pairingTimer.record(pairing);
    }
}
