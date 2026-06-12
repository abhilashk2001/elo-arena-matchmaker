package com.eloarena.matchmaking;

/**
 * Lifecycle of a queue entry. Mirrors the chk_queue_status check constraint in the schema.
 */
public enum QueueStatus {
    WAITING,
    MATCHED,
    CANCELLED
}
