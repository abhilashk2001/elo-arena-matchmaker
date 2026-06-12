package com.eloarena.matchmaking;

/**
 * Thrown when a player tries to join the queue while they already have a WAITING entry.
 * Surfaced from the uq_player_waiting partial unique index; mapped to 409 by the handler.
 */
public class AlreadyQueuedException extends RuntimeException {

    public AlreadyQueuedException(long playerId) {
        super("Player " + playerId + " is already waiting in the queue.");
    }
}
