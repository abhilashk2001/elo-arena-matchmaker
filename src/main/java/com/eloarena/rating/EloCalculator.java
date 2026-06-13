package com.eloarena.rating;

import org.springframework.stereotype.Component;

/**
 * Hand-rolled Elo. The expected score is a logistic function of the rating gap, and a player's
 * rating moves by K times the difference between the actual result (1 for a win, 0 for a loss)
 * and that expected score. Beating a stronger opponent gains more than beating a weaker one,
 * and the points the winner gains equal the points the loser drops.
 *
 * K is a flat 32. Real systems use a rating-dependent K and provisional periods for new
 * players; those are deliberate non-goals here.
 */
@Component
public class EloCalculator {

    public static final int K = 32;

    /**
     * Compute both players' new ratings for a completed match.
     *
     * @param ratingA current rating of player A
     * @param ratingB current rating of player B
     * @param aWon    true if A won, false if B won (no draws in this system)
     */
    public EloResult compute(int ratingA, int ratingB, boolean aWon) {
        double expectedA = expectedScore(ratingA, ratingB);
        double expectedB = expectedScore(ratingB, ratingA);

        double scoreA = aWon ? 1.0 : 0.0;
        double scoreB = aWon ? 0.0 : 1.0;

        int newA = (int) Math.round(ratingA + K * (scoreA - expectedA));
        int newB = (int) Math.round(ratingB + K * (scoreB - expectedB));
        return new EloResult(newA, newB);
    }

    /** Expected score (win probability) of the player rated {@code rating} against {@code opponent}. */
    public double expectedScore(int rating, int opponent) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponent - rating) / 400.0));
    }

    /** The new ratings for both players after a match. */
    public record EloResult(int newRatingA, int newRatingB) {
    }
}
