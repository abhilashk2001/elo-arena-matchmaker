package com.eloarena.season;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeasonRepository extends JpaRepository<Season, Long> {

    Optional<Season> findFirstByEndedAtIsNull();

    List<Season> findAllByOrderByIdAsc();
}
