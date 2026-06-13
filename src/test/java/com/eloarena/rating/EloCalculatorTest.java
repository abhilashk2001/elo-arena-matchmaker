package com.eloarena.rating;

import com.eloarena.rating.EloCalculator.EloResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests against hand-computed Elo values (K = 32).
 */
class EloCalculatorTest {

    private final EloCalculator elo = new EloCalculator();

    @Test
    void equalRatingsSplitEvenly() {
        // expected = 0.5 each; winner +16, loser -16.
        EloResult result = elo.compute(1500, 1500, true);
        assertThat(result.newRatingA()).isEqualTo(1516);
        assertThat(result.newRatingB()).isEqualTo(1484);
    }

    @Test
    void favouriteWinningGainsLittle() {
        // A is 200 higher and wins as expected: small gain.
        EloResult result = elo.compute(1600, 1400, true);
        assertThat(result.newRatingA()).isEqualTo(1608);
        assertThat(result.newRatingB()).isEqualTo(1392);
    }

    @Test
    void upsetWinGainsMuch() {
        // A is 200 lower and wins the upset: large gain.
        EloResult result = elo.compute(1400, 1600, true);
        assertThat(result.newRatingA()).isEqualTo(1424);
        assertThat(result.newRatingB()).isEqualTo(1576);
    }

    @Test
    void pointsAreConserved() {
        EloResult result = elo.compute(1730, 1290, false);
        assertThat(result.newRatingA() + result.newRatingB()).isEqualTo(1730 + 1290);
    }
}
