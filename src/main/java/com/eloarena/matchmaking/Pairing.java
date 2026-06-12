package com.eloarena.matchmaking;

/**
 * Two candidates the matcher has decided to pair into a match.
 */
public record Pairing(Candidate a, Candidate b) {

    public int ratingDelta() {
        return Math.abs(a.rating() - b.rating());
    }
}
