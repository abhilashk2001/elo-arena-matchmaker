package com.eloarena.matchmaking;

/**
 * Thrown when a leave request finds no WAITING entry to cancel for the player, whether
 * because they never queued or because the matcher already matched them. Mapped to 404.
 */
public class NotQueuedException extends RuntimeException {

    public NotQueuedException(long playerId) {
        super("Player " + playerId + " has no active queue entry to leave.");
    }
}
