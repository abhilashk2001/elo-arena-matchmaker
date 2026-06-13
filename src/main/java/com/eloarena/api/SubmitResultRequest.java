package com.eloarena.api;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for submitting a match result.
 */
public record SubmitResultRequest(@NotNull Long winnerId) {
}
