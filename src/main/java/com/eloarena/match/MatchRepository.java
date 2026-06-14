package com.eloarena.match;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long> {

    long countByPlayerAIdOrPlayerBId(Long playerAId, Long playerBId);

    /**
     * Ids of a player's IN_PROGRESS matches created since {@code since}. Anomaly detection uses the
     * recent window so a stale orphaned match (a naive duplicate that never completed, or a match
     * left open when the simulator stopped) is not mistaken for a concurrent double-booking when the
     * player later rematches under locking.
     */
    @Query("""
            SELECT m.id FROM Match m
             WHERE (m.playerAId = :playerId OR m.playerBId = :playerId)
               AND m.status = com.eloarena.match.MatchStatus.IN_PROGRESS
               AND m.createdAt > :since
            """)
    List<Long> findRecentInProgressMatchIdsForPlayer(@Param("playerId") long playerId,
                                                     @Param("since") Instant since);

    /**
     * The live double-booking gauge: how many players are currently in more than one IN_PROGRESS
     * match created since {@code since}. Reads zero under locking, climbs under naive, and recovers
     * once the offending matches complete or age out of the window. Native because it groups over a
     * union of both player columns.
     */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT player_id FROM (
                    SELECT player_a_id AS player_id FROM matches
                     WHERE status = 'IN_PROGRESS' AND created_at > :since
                    UNION ALL
                    SELECT player_b_id FROM matches
                     WHERE status = 'IN_PROGRESS' AND created_at > :since
                ) p
                GROUP BY player_id
                HAVING count(*) > 1
            ) doubled
            """, nativeQuery = true)
    long countDoubleBookedPlayers(@Param("since") Instant since);

    /**
     * Load a match with a pessimistic write lock so two simultaneous result submissions for the
     * same match serialize: the second waits for the first to commit, then sees it COMPLETED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Match m WHERE m.id = :id")
    Optional<Match> findByIdForUpdate(@Param("id") long id);
}
