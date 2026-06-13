package com.eloarena.rating;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RatingHistoryRepository extends JpaRepository<RatingHistory, Long> {

    List<RatingHistory> findByMatchId(long matchId);

    List<RatingHistory> findByPlayerIdOrderByCreatedAtDesc(long playerId);
}
