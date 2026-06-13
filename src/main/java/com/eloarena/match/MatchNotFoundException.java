package com.eloarena.match;

/**
 * Thrown when a result is submitted for a match id that does not exist. Mapped to 404.
 */
public class MatchNotFoundException extends RuntimeException {

    public MatchNotFoundException(long matchId) {
        super("Match " + matchId + " does not exist.");
    }
}
