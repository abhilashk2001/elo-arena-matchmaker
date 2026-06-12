package com.eloarena.api;

import java.time.Instant;

/**
 * Uniform error body returned for every handled API error:
 * { "error": "CODE", "message": "human readable", "timestamp": "..." }.
 */
public record ErrorResponse(String error, String message, Instant timestamp) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Instant.now());
    }
}
