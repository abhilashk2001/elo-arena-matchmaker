package com.eloarena.matchmaking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable matcher settings, bound from the eloarena.matcher.* config keys.
 * Band expansion controls how a waiting player's acceptable rating window grows over time.
 */
@ConfigurationProperties(prefix = "eloarena.matcher")
public record MatcherProperties(Band band) {

    /**
     * Band expansion parameters.
     *
     * @param base               the starting half-width of a player's rating window, in rating points
     * @param expansionPerSecond how many rating points the half-width grows per second of waiting
     * @param max                the cap on the half-width, so nobody is matched against an absurdly far opponent
     */
    public record Band(int base, int expansionPerSecond, int max) {
    }
}
