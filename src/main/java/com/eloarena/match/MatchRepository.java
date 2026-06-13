package com.eloarena.match;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long> {

    long countByPlayerAIdOrPlayerBId(Long playerAId, Long playerBId);

    /** Ids of all IN_PROGRESS matches a player is currently part of. */
    @Query("""
            SELECT m.id FROM Match m
             WHERE (m.playerAId = :playerId OR m.playerBId = :playerId)
               AND m.status = com.eloarena.match.MatchStatus.IN_PROGRESS
            """)
    List<Long> findInProgressMatchIdsForPlayer(@Param("playerId") long playerId);

    /**
     * Load a match with a pessimistic write lock so two simultaneous result submissions for the
     * same match serialize: the second waits for the first to commit, then sees it COMPLETED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Match m WHERE m.id = :id")
    Optional<Match> findByIdForUpdate(@Param("id") long id);
}
