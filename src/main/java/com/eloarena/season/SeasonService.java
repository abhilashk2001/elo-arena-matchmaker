package com.eloarena.season;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal season access for the matcher. Phase 6 builds the full lifecycle (end season,
 * snapshot, soft reset) on top of this.
 */
@Service
public class SeasonService {

    private final SeasonRepository seasons;

    public SeasonService(SeasonRepository seasons) {
        this.seasons = seasons;
    }

    /**
     * The id of the active season, creating an initial "Season 1" if none exists.
     * If two callers race to create it, the single-active-season unique index rejects the
     * loser, which then reads the winner's season.
     */
    @Transactional
    public long currentSeasonId() {
        return seasons.findFirstByEndedAtIsNull()
                .map(Season::getId)
                .orElseGet(this::createInitialSeason);
    }

    private long createInitialSeason() {
        try {
            return seasons.save(new Season("Season 1")).getId();
        } catch (DataIntegrityViolationException raced) {
            return seasons.findFirstByEndedAtIsNull().orElseThrow().getId();
        }
    }
}
