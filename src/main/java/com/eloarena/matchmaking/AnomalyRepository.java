package com.eloarena.matchmaking;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {

    List<Anomaly> findAllByOrderByDetectedAtDesc(Pageable pageable);
}
