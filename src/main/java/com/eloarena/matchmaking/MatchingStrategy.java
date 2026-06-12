package com.eloarena.matchmaking;

/**
 * A strategy for turning waiting players into matches on each matcher tick.
 *
 * Two implementations exist behind this interface, switchable at runtime:
 * NaiveMatcher (a plain read with no locks, intentionally unsafe under concurrency) and
 * LockingMatcher (claims rows with FOR UPDATE SKIP LOCKED). They differ only in how they
 * acquire the waiting rows; the pairing decisions are shared.
 */
public interface MatchingStrategy {

    /** Identifier used by config and the runtime toggle, e.g. "naive" or "locking". */
    String name();

    /**
     * Run one matching pass: acquire waiting players, pair compatible ones, and persist the
     * resulting matches. Returns the number of matches created this tick.
     */
    int matchTick();
}
