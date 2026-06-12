package com.eloarena.match;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchRepository extends JpaRepository<Match, Long> {

    long countByPlayerAIdOrPlayerBId(Long playerAId, Long playerBId);
}
