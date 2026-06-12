package com.eloarena.season;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal season access for the matcher. The initial season is seeded by migration and
 * Phase 6's rollover always keeps exactly one season active, so this only ever reads.
 */
@Service
public class SeasonService {

    private final SeasonRepository seasons;

    public SeasonService(SeasonRepository seasons) {
        this.seasons = seasons;
    }

    /** The id of the active season. */
    @Transactional(readOnly = true)
    public long currentSeasonId() {
        return seasons.findFirstByEndedAtIsNull()
                .map(Season::getId)
                .orElseThrow(() -> new IllegalStateException("No active season found"));
    }
}
