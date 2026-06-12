package com.eloarena.match;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchRepository extends JpaRepository<Match, Long> {

    long countByPlayerAIdOrPlayerBId(Long playerAId, Long playerBId);

    /** Ids of all IN_PROGRESS matches a player is currently part of. */
    @Query("""
            SELECT m.id FROM Match m
             WHERE (m.playerAId = :playerId OR m.playerBId = :playerId)
               AND m.status = com.eloarena.match.MatchStatus.IN_PROGRESS
            """)
    List<Long> findInProgressMatchIdsForPlayer(@Param("playerId") long playerId);
}
