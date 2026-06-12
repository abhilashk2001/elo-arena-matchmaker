package com.eloarena.match;

/**
 * Lifecycle of a match. Mirrors the chk_match_status check constraint in the schema.
 */
public enum MatchStatus {
    IN_PROGRESS,
    COMPLETED
}
