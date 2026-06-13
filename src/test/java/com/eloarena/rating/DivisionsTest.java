package com.eloarena.rating;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the rating-to-division mapping (10 divisions, 200 points each).
 */
class DivisionsTest {

    private final Divisions divisions = new Divisions();

    @Test
    void floorRatingIsTheBottomDivision() {
        assertThat(divisions.divisionFor(400)).isEqualTo(10);
        assertThat(divisions.divisionFor(599)).isEqualTo(10);
    }

    @Test
    void boundariesStepUpEvery200Points() {
        assertThat(divisions.divisionFor(600)).isEqualTo(9);
        assertThat(divisions.divisionFor(1500)).isEqualTo(5);
        assertThat(divisions.divisionFor(2199)).isEqualTo(2);
        assertThat(divisions.divisionFor(2200)).isEqualTo(1);
    }

    @Test
    void ratingsOutsideTheRangeClamp() {
        assertThat(divisions.divisionFor(350)).isEqualTo(10);   // below the floor
        assertThat(divisions.divisionFor(3000)).isEqualTo(1);   // top of the seed range
    }
}
