package com.eloarena.rating;

import org.springframework.stereotype.Component;

/**
 * Maps a rating to a competitive division. Divisions are DERIVED from rating, never stored:
 * a stored copy would be a second value that could drift from the rating it mirrors, which is
 * the kind of inconsistency this project exists to avoid.
 *
 * Ten divisions, 200 rating points each. Division 1 is the top (rating at or above
 * FLOOR + 9*WIDTH = 2200); Division 10 is the bottom (around the seed floor of 400). Ratings
 * outside the range clamp to the nearest division.
 */
@Component
public class Divisions {

    public static final int COUNT = 10;
    public static final int WIDTH = 200;
    public static final int FLOOR = 400;

    /** The division (1 = top, 10 = bottom) a given rating falls into. */
    public int divisionFor(int rating) {
        int fromBottom = Math.floorDiv(rating - FLOOR, WIDTH); // 0 at the floor, grows with rating
        int division = COUNT - fromBottom;
        return Math.max(1, Math.min(COUNT, division));
    }
}
