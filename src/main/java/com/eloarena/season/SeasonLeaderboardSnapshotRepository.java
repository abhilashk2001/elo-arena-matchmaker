package com.eloarena.season;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeasonLeaderboardSnapshotRepository extends JpaRepository<SeasonLeaderboardSnapshot, Long> {

    List<SeasonLeaderboardSnapshot> findBySeasonIdOrderByFinalRankAsc(long seasonId, Pageable pageable);
}
