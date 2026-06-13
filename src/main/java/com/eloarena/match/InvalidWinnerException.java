package com.eloarena.match;

/**
 * Thrown when a submitted winner is not one of the two players in the match. Mapped to 422.
 */
public class InvalidWinnerException extends RuntimeException {

    public InvalidWinnerException(long matchId, long winnerId) {
        super("Player " + winnerId + " is not a participant in match " + matchId + ".");
    }
}
