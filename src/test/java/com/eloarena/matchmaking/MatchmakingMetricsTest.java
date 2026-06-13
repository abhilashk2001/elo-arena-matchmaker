package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatchmakingMetricsTest extends IntegrationTest {

    @Autowired
    private MatchmakingMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void matchesCreatedIsCountedPerStrategy() {
        double before = strategyCount("locking");

        metrics.matchesCreated("locking", 3);
        metrics.matchesCreated("naive", 2);

        assertThat(strategyCount("locking") - before).isEqualTo(3.0);
        assertThat(strategyCount("naive")).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void queueWaitAndPairingTimersRecord() {
        long pairingBefore = registry.get("eloarena.pairing.duration").timer().count();

        metrics.recordQueueWait(150);
        metrics.timePairing(() -> "done");

        assertThat(registry.get("eloarena.queue.wait").timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("eloarena.pairing.duration").timer().count()).isGreaterThan(pairingBefore);
    }

    @Test
    void queueDepthGaugeIsRegistered() {
        assertThat(registry.find("eloarena.queue.depth").gauge()).isNotNull();
    }

    @Test
    void metersAreExposedViaActuator() throws Exception {
        metrics.matchesCreated("locking", 1);
        mockMvc.perform(get("/actuator/metrics/eloarena.matches.created"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("eloarena.matches.created"));
    }

    private double strategyCount(String strategy) {
        var counter = registry.find("eloarena.matches.created").tag("strategy", strategy).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
