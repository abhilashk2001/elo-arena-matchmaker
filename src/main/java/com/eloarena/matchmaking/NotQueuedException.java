package com.eloarena.matchmaking;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a leave request finds no WAITING entry to cancel for the player, whether
 * because they never queued or because the matcher already matched them. Mapped to 404.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotQueuedException extends RuntimeException {

    public NotQueuedException(long playerId) {
        super("Player " + playerId + " has no active queue entry to leave.");
    }
}
