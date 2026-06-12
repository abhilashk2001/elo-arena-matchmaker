package com.eloarena.player;

/**
 * Thrown when an operation references a player id that does not exist.
 * Mapped to a 404 with a uniform error body by the global exception handler.
 */
public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(long playerId) {
        super("Player " + playerId + " does not exist.");
    }
}
