package com.eloarena.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

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
