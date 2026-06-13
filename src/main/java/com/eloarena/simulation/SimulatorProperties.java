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
 */
@ConfigurationProperties(prefix = "eloarena.simulator")
public record SimulatorProperties(String baseUrl, long pollMs, int maxPolls) {

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
    }
}
