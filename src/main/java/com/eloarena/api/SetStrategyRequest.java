package com.eloarena.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for switching the matcher strategy.
 */
public record SetStrategyRequest(@NotBlank String strategy) {
}
