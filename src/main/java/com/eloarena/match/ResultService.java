package com.eloarena.match;

import com.eloarena.api.MatchResultResponse;
import com.eloarena.api.MatchResultResponse.RatingChange;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.rating.EloCalculator;
import com.eloarena.rating.EloCalculator.EloResult;
import com.eloarena.rating.RatingHistory;
import com.eloarena.rating.RatingHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Processes a match result in one transaction: lock the match, validate, compute Elo, update
 * both players, record two rating-history rows, and complete the match. Postgres is the source
 * of truth here; the Redis leaderboard update happens after commit (added in story #37).
 */
@Service
public class ResultService {

    private final MatchRepository matches;
    private final PlayerRepository players;
    private final RatingHistoryRepository history;
    private final EloCalculator elo;
    private final ApplicationEventPublisher events;
    private final Timer processingTimer;

    public ResultService(MatchRepository matches,
                         PlayerRepository players,
                         RatingHistoryRepository history,
                         EloCalculator elo,
                         ApplicationEventPublisher events,
                         MeterRegistry meterRegistry) {
        this.matches = matches;
        this.players = players;
        this.history = history;
        this.elo = elo;
        this.events = events;
        this.processingTimer = Timer.builder("eloarena.result.processing")
                .description("Time to process a match result, the hot-row contention probe")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Transactional
    public MatchResultResponse submit(long matchId, long winnerId) {
        return processingTimer.record(() -> process(matchId, winnerId));
    }

    private MatchResultResponse process(long matchId, long winnerId) {
        Match match = matches.findByIdForUpdate(matchId)
                .orElseThrow(() -> new MatchNotFoundException(matchId));

        // Idempotent replay: a result already applied returns the existing outcome, not an error.
        if (match.getStatus() == MatchStatus.COMPLETED) {
            return buildResponse(match);
        }

        long playerAId = match.getPlayerAId();
        long playerBId = match.getPlayerBId();
        if (winnerId != playerAId && winnerId != playerBId) {
            throw new InvalidWinnerException(matchId, winnerId);
        }

        Player playerA = players.findById(playerAId).orElseThrow();
        Player playerB = players.findById(playerBId).orElseThrow();
        boolean aWon = winnerId == playerAId;

        int beforeA = playerA.getRating();
        int beforeB = playerB.getRating();
        EloResult newRatings = elo.compute(beforeA, beforeB, aWon);

        applyRating(playerA, newRatings.newRatingA());
        applyRating(playerB, newRatings.newRatingB());

        history.save(new RatingHistory(playerAId, matchId, match.getSeasonId(), beforeA, newRatings.newRatingA()));
        history.save(new RatingHistory(playerBId, matchId, match.getSeasonId(), beforeB, newRatings.newRatingB()));

        match.complete(winnerId, Instant.now());

        // Update the Redis leaderboard only after this transaction commits.
        events.publishEvent(new MatchCompletedEvent(
                match.getSeasonId(),
                playerAId, newRatings.newRatingA(),
                playerBId, newRatings.newRatingB()));

        return buildResponse(match);
    }

    private void applyRating(Player player, int newRating) {
        player.setRating(newRating);
        player.setGamesPlayed(player.getGamesPlayed() + 1);
    }

    private MatchResultResponse buildResponse(Match match) {
        List<RatingChange> changes = history.findByMatchId(match.getId()).stream()
                .map(h -> new RatingChange(h.getPlayerId(), h.getRatingBefore(), h.getRatingAfter()))
                .toList();
        return new MatchResultResponse(match.getId(), match.getWinnerId(), match.getStatus().name(), changes);
    }
}
