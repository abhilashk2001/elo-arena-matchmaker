package com.eloarena.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A player in the system. Maps to the players table created in V1__init.sql.
 *
 * Column names follow Spring Boot's default snake_case naming strategy
 * (gamesPlayed maps to games_played, createdAt to created_at).
 */
@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String handle;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private int gamesPlayed;

    // Set by the database default (now()). Hibernate reads it but never writes it.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Player() {
        // Required by JPA.
    }

    /**
     * Create a new player. Rating defaults to 1200 (matching the column default)
     * unless a starting rating is supplied by the seeder.
     */
    public Player(String handle, int rating, String region) {
        this.handle = handle;
        this.rating = rating;
        this.region = region;
        this.gamesPlayed = 0;
    }

    public Long getId() {
        return id;
    }

    public String getHandle() {
        return handle;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }

    public String getRegion() {
        return region;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(int gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
