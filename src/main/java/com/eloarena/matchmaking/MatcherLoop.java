package com.eloarena.matchmaking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the matcher on a fixed schedule. Present only when eloarena.matcher.loop-enabled
 * is true (default and matcher-only profiles; off for api-only and during tests).
 *
 * For now it runs the NaiveMatcher directly. Story #29 replaces this with a runtime-
 * switchable strategy so the dashboard can flip between naive and locking live.
 */
@Component
@ConditionalOnProperty(name = "eloarena.matcher.loop-enabled", havingValue = "true", matchIfMissing = false)
public class MatcherLoop {

    private static final Logger log = LoggerFactory.getLogger(MatcherLoop.class);

    private final NaiveMatcher matcher;

    public MatcherLoop(NaiveMatcher matcher) {
        this.matcher = matcher;
    }

    @Scheduled(fixedDelayString = "${eloarena.matcher.interval-ms:1000}")
    public void tick() {
        try {
            int created = matcher.matchTick();
            if (created > 0) {
                log.info("Matcher created {} match(es) this tick", created);
            }
        } catch (Exception e) {
            // A failing tick must not kill the scheduler; log and try again next tick.
            log.error("Matcher tick failed", e);
        }
    }
}
