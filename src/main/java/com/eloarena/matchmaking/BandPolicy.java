package com.eloarena.matchmaking;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Implements the band-expansion compatibility rule.
 *
 * A waiting player accepts opponents within a rating half-width (the "band") that starts
 * at base and grows by expansionPerSecond for every second they wait, capped at max. This
 * lets rare tail players eventually match instead of starving.
 *
 * Compatibility is symmetric: two players match only if each is inside the other's current
 * band. Because they may have waited different amounts of time, their bands can differ, so
 * both directions must be checked.
 */
@Component
public class BandPolicy {

    private final MatcherProperties.Band band;

    public BandPolicy(MatcherProperties properties) {
        this.band = properties.band();
    }

    /** The current band half-width for a candidate at time {@code now}. */
    public int bandFor(Candidate candidate, Instant now) {
        long waitSeconds = Math.max(0, Duration.between(candidate.enqueuedAt(), now).getSeconds());
        long width = band.base() + (long) band.expansionPerSecond() * waitSeconds;
        return (int) Math.min(width, band.max());
    }

    /** True if each candidate is within the other's current band. */
    public boolean compatible(Candidate a, Candidate b, Instant now) {
        int delta = Math.abs(a.rating() - b.rating());
        return delta <= bandFor(a, now) && delta <= bandFor(b, now);
    }
}
