package com.eloarena.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

    /** All waiting entries, longest waiter first. Used by the naive matcher's plain read. */
    List<QueueEntry> findByStatusOrderByEnqueuedAtAsc(QueueStatus status);

    long countByStatus(QueueStatus status);

    /**
     * Mark a queue entry MATCHED and link it to its match. The naive matcher updates by id
     * without a status guard, on purpose: under concurrency two matchers can both reach
     * this for the same entry, which is part of the race Phase 3 demonstrates.
     */
    @Modifying
    @Query("""
            UPDATE QueueEntry q
               SET q.status = com.eloarena.matchmaking.QueueStatus.MATCHED,
                   q.matchedMatchId = :matchId
             WHERE q.id = :entryId
            """)
    int markMatched(@Param("entryId") long entryId, @Param("matchId") long matchId);

    /**
     * Cancel a player's WAITING entry in a single conditional update.
     *
     * The {@code status = WAITING} guard is what makes leaving safe against the matcher:
     * if the matcher has already flipped the entry to MATCHED, this matches zero rows and
     * we never cancel a matched entry. Returns the number of rows updated (0 or 1).
     */
    @Modifying
    @Query("""
            UPDATE QueueEntry q
               SET q.status = com.eloarena.matchmaking.QueueStatus.CANCELLED
             WHERE q.playerId = :playerId
               AND q.status = com.eloarena.matchmaking.QueueStatus.WAITING
            """)
    int cancelWaiting(@Param("playerId") long playerId);
}
