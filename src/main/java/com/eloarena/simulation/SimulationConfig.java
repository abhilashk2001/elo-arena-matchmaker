package com.eloarena.simulation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * One simulation run's parameters, posted to the start endpoint.
 *
 * @param players              how many distinct seeded players to drive, one virtual thread each
 * @param requeueProbability   chance a player rejoins the queue after finishing a match (0..1);
 *                             this is what keeps the queue under sustained load
 * @param matchDurationMinMs   shortest fake match duration, in milliseconds
 * @param matchDurationMaxMs   longest fake match duration; the actual duration is uniformly
 *                             random in [min, max], which is the jitter
 */
public record SimulationConfig(
        @Min(1) @Max(200_000) int players,
        @DecimalMin("0.0") @DecimalMax("1.0") double requeueProbability,
        @Min(0) long matchDurationMinMs,
        @Min(0) long matchDurationMaxMs) {

    public SimulationConfig {
        if (matchDurationMaxMs < matchDurationMinMs) {
            throw new IllegalArgumentException(
                    "matchDurationMaxMs (" + matchDurationMaxMs + ") must be >= matchDurationMinMs (" + matchDurationMinMs + ")");
        }
    }
}
