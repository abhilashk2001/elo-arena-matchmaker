package com.eloarena.leaderboard;

import com.eloarena.match.MatchCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Updates the Redis leaderboard after a match result commits. Bound to AFTER_COMMIT, so if the
 * result transaction rolls back the listener never runs and Redis is never given a rating the
 * truth store did not record.
 */
@Component
public class LeaderboardUpdater {

    private final Leaderboard leaderboard;

    public LeaderboardUpdater(Leaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatchCompleted(MatchCompletedEvent event) {
        leaderboard.addRating(event.seasonId(), event.playerAId(), event.newRatingA());
        leaderboard.addRating(event.seasonId(), event.playerBId(), event.newRatingB());
    }
}
