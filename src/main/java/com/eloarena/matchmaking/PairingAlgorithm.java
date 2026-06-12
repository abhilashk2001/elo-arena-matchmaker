package com.eloarena.matchmaking;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Greedy pairing shared by both matching strategies. Walks candidates oldest-first and, for
 * each still-unpaired candidate, pairs it with the compatible partner of minimum rating
 * delta (closest skill). Compatibility comes from {@link BandPolicy}.
 *
 * This is O(n^2) within the set it is given. That is fine because strategies hand it a
 * bounded batch (at most a couple hundred rows); we deliberately do not optimise it early.
 */
@Component
public class PairingAlgorithm {

    private final BandPolicy bandPolicy;

    public PairingAlgorithm(BandPolicy bandPolicy) {
        this.bandPolicy = bandPolicy;
    }

    public List<Pairing> pair(List<Candidate> candidates, Instant now) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(Candidate::enqueuedAt));

        boolean[] taken = new boolean[ordered.size()];
        List<Pairing> pairings = new ArrayList<>();

        for (int i = 0; i < ordered.size(); i++) {
            if (taken[i]) {
                continue;
            }
            Candidate a = ordered.get(i);

            int bestIndex = -1;
            int bestDelta = Integer.MAX_VALUE;
            for (int j = i + 1; j < ordered.size(); j++) {
                if (taken[j]) {
                    continue;
                }
                Candidate b = ordered.get(j);
                if (!bandPolicy.compatible(a, b, now)) {
                    continue;
                }
                int delta = Math.abs(a.rating() - b.rating());
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestIndex = j;
                }
            }

            if (bestIndex >= 0) {
                taken[i] = true;
                taken[bestIndex] = true;
                pairings.add(new Pairing(a, ordered.get(bestIndex)));
            }
        }
        return pairings;
    }
}
