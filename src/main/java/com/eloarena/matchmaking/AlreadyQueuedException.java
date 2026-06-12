package com.eloarena.matchmaking;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a player tries to join the queue while they already have a WAITING entry.
 * Surfaced from the uq_player_waiting partial unique index, mapped to 409 Conflict.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AlreadyQueuedException extends RuntimeException {

    public AlreadyQueuedException(long playerId) {
        super("Player " + playerId + " is already waiting in the queue.");
    }
}
