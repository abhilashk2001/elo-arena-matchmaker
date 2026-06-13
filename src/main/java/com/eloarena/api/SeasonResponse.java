package com.eloarena.api;

import java.time.Instant;

/**
 * A season's summary. active is true when the season has not ended.
 */
public record SeasonResponse(Long id, String name, Instant startedAt, Instant endedAt, boolean active) {
}
