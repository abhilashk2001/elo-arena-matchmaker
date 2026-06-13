package com.eloarena.match;

import com.eloarena.api.MatchResultResponse;
import com.eloarena.api.MatchResultResponse.RatingChange;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.rating.EloCalculator;
import com.eloarena.rating.EloCalculator.EloResult;
import com.eloarena.rating.RatingHistory;
import com.eloarena.rating.RatingHistoryRepository;
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

    public ResultService(MatchRepository matches,
                         PlayerRepository players,
                         RatingHistoryRepository history,
                         EloCalculator elo) {
        this.matches = matches;
        this.players = players;
        this.history = history;
        this.elo = elo;
    }

    @Transactional
    public MatchResultResponse submit(long matchId, long winnerId) {
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
