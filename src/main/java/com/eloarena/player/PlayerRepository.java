package com.eloarena.player;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository over the players table. Standard CRUD comes from JpaRepository;
 * hot-path queries that need precise control are written by hand with JdbcTemplate
 * in later phases (the matcher), not here.
 */
public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByHandle(String handle);

    boolean existsByHandle(String handle);
}
