package com.eloarena.player;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an operation references a player id that does not exist.
 * The global exception handler (story #22) will give this a uniform error body;
 * the @ResponseStatus already ensures the correct 404 status in the meantime.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(long playerId) {
        super("Player " + playerId + " does not exist.");
    }
}
