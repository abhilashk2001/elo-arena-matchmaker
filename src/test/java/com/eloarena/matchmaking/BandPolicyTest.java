package com.eloarena.matchmaking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the band-expansion rule. No Spring context or database needed:
 * the policy is a plain function of config, ratings, and wait time.
 */
class BandPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    // base 50, +5 per second, capped at 400 (the production defaults).
    private final BandPolicy policy =
            new BandPolicy(new MatcherProperties(new MatcherProperties.Band(50, 5, 400), 100));

    private Candidate candidateWaiting(int rating, Duration waited) {
        return new Candidate(1L, 1L, rating, NOW.minus(waited));
    }

    @Test
    void bandStartsAtBaseWithNoWait() {
        assertThat(policy.bandFor(candidateWaiting(1200, Duration.ZERO), NOW)).isEqualTo(50);
    }

    @Test
    void bandGrowsWithWaitTime() {
        assertThat(policy.bandFor(candidateWaiting(1200, Duration.ofSeconds(10)), NOW)).isEqualTo(100);
        assertThat(policy.bandFor(candidateWaiting(1200, Duration.ofSeconds(30)), NOW)).isEqualTo(200);
    }

    @Test
    void bandIsCappedAtMax() {
        // 50 + 5*1000 = 5050, capped to 400.
        assertThat(policy.bandFor(candidateWaiting(1200, Duration.ofSeconds(1000)), NOW)).isEqualTo(400);
    }

    @Test
    void closeRatingsAreCompatibleImmediately() {
        Candidate a = candidateWaiting(1200, Duration.ZERO);
        Candidate b = candidateWaiting(1240, Duration.ZERO);
        assertThat(policy.compatible(a, b, NOW)).isTrue(); // delta 40 <= band 50
    }

    @Test
    void slightlyFarRatingsNeedTimeToBecomeCompatible() {
        Candidate a = candidateWaiting(1200, Duration.ZERO);
        Candidate b = candidateWaiting(1260, Duration.ZERO);
        assertThat(policy.compatible(a, b, NOW)).isFalse(); // delta 60 > band 50

        Candidate aWaited = candidateWaiting(1200, Duration.ofSeconds(10));
        Candidate bWaited = candidateWaiting(1260, Duration.ofSeconds(10));
        assertThat(policy.compatible(aWaited, bWaited, NOW)).isTrue(); // delta 60 <= band 100
    }

    @Test
    void compatibilityIsSymmetric() {
        // a has waited a long time (band 400), b just joined (band 50), delta is 200.
        // a would accept b, but b would not accept a, so they are not compatible.
        Candidate aPatient = candidateWaiting(1000, Duration.ofSeconds(1000));
        Candidate bFresh = candidateWaiting(1200, Duration.ZERO);
        assertThat(policy.compatible(aPatient, bFresh, NOW)).isFalse();
    }
}
