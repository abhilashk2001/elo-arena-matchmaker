package com.eloarena.api;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for joining the queue.
 */
public record JoinQueueRequest(@NotNull Long playerId) {
}
