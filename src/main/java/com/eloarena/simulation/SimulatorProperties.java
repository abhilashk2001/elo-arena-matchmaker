package com.eloarena.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the in-app load simulator, bound from eloarena.simulator.*.
 *
 * @param baseUrl   the base URL the simulator drives. It calls the application's own HTTP API
 *                  (not the services directly) so the load is realistic: serialization, the
 *                  servlet stack, the connection pool, everything a real client would hit.
 * @param pollMs    how long each simulated player waits between queue-status polls
 * @param maxPolls  give-up bound on polling for a match, so a player who is never paired (an odd
 *                  one out) ends cleanly instead of spinning forever
 * @param rampUpMs  spread the initial join over this window instead of having every player join at
 *                  once. A synchronized burst of tens of thousands of joins opens both an inbound
 *                  and an outbound socket per player in this single JVM, which blows the per-process
 *                  file-descriptor limit on the spot; ramping the start keeps concurrent sockets to
 *                  the modest steady-state level. 0 (the default) means no ramp, which is fine for
 *                  the small player counts used in tests.
 */
@ConfigurationProperties(prefix = "eloarena.simulator")
public record SimulatorProperties(String baseUrl, long pollMs, int maxPolls, long rampUpMs) {

    public SimulatorProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        if (pollMs <= 0) {
            pollMs = 200;
        }
        if (maxPolls <= 0) {
            maxPolls = 300;
        }
        if (rampUpMs < 0) {
            rampUpMs = 0;
        }
    }
}
