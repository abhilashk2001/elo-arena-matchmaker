package com.eloarena.simulation;

import com.eloarena.rating.EloCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-app load generator. It drives the real HTTP API exactly as an external client would
 * (join, poll for a match, play it out, submit a result, maybe requeue), one virtual thread
 * per simulated player. Virtual threads are the right tool here: each player spends almost all
 * its time blocked on HTTP and sleeps, so we can run tens of thousands of them on a handful of
 * carrier threads without a giant pool.
 *
 * The simulator deliberately calls the API over HTTP rather than invoking the services directly,
 * so the load exercises the whole stack (serialization, the servlet layer, the connection pool)
 * which is what the benchmarks in this phase are measuring.
 */
@Service
public class Simulator {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    private final SimulatorProperties properties;
    private final JdbcTemplate jdbc;
    private final EloCalculator elo;

    private volatile boolean running;
    private volatile ExecutorService executor;
    private final AtomicInteger activePlayers = new AtomicInteger();

    public Simulator(SimulatorProperties properties, JdbcTemplate jdbc, EloCalculator elo) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.elo = elo;
    }

    public synchronized SimulationStatus start(SimulationConfig config) {
        if (running) {
            throw new IllegalStateException("Simulation already running with " + activePlayers.get() + " players");
        }
        List<Long> playerIds = jdbc.queryForList(
                "SELECT id FROM players ORDER BY id LIMIT ?", Long.class, config.players());
        if (playerIds.isEmpty()) {
            throw new IllegalStateException("No players seeded. Call POST /api/admin/seed first.");
        }

        RestClient client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                // Never treat any status as an error: the loop inspects status codes itself,
                // so a 409 (already queued) or 404 is handled, not thrown as an exception.
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();

        running = true;
        activePlayers.set(playerIds.size());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (Long playerId : playerIds) {
            executor.submit(() -> runPlayer(playerId, config, client));
        }
        log.info("Simulation started: {} players, requeueProbability={}", playerIds.size(), config.requeueProbability());
        return new SimulationStatus(true, playerIds.size());
    }

    public synchronized SimulationStatus stop() {
        if (!running) {
            return new SimulationStatus(false, 0);
        }
        running = false;
        ExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
        executor = null;
        int wasActive = activePlayers.getAndSet(0);
        log.info("Simulation stopped ({} players were active)", wasActive);
        return new SimulationStatus(false, wasActive);
    }

    public SimulationStatus status() {
        return new SimulationStatus(running, running ? activePlayers.get() : 0);
    }

    /** The full lifecycle of one simulated player, repeated while it keeps requeuing. */
    private void runPlayer(long playerId, SimulationConfig config, RestClient client) {
        try {
            // Stagger the initial join across the ramp window so all the players do not open their
            // sockets in the same instant; see SimulatorProperties.rampUpMs for why that matters.
            long rampUpMs = properties.rampUpMs();
            if (rampUpMs > 0) {
                Thread.sleep(ThreadLocalRandom.current().nextLong(rampUpMs));
            }
            do {
                if (!running) {
                    return;
                }
                if (!join(client, playerId)) {
                    return;
                }
                Long matchId = pollUntilMatched(client, playerId);
                if (matchId == null) {
                    return;
                }
                MatchView match = fetchMatch(client, matchId);
                if (match == null) {
                    return;
                }
                sleepMatchDuration(config);
                submitResult(client, matchId, pickWinner(match));
            } while (running && ThreadLocalRandom.current().nextDouble() < config.requeueProbability());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // One player failing must never take down the run; just end this player's loop.
            log.debug("Simulated player {} ended early: {}", playerId, e.toString());
        } finally {
            activePlayers.decrementAndGet();
        }
    }

    private boolean join(RestClient client, long playerId) {
        var response = client.post()
                .uri("/api/queue/join")
                .body(new JoinBody(playerId))
                .retrieve()
                .toBodilessEntity();
        HttpStatusCode status = response.getStatusCode();
        // 201 created, or 409 already queued (a leftover WAITING entry we can still poll on).
        return status.is2xxSuccessful() || status.value() == 409;
    }

    private Long pollUntilMatched(RestClient client, long playerId) throws InterruptedException {
        for (int poll = 0; poll < properties.maxPolls() && running; poll++) {
            var response = client.get()
                    .uri("/api/queue/{playerId}", playerId)
                    .retrieve()
                    .toEntity(QueuePollView.class);
            QueuePollView body = response.getBody();
            if (response.getStatusCode().is2xxSuccessful() && body != null
                    && "MATCHED".equals(body.status()) && body.matchedMatchId() != null) {
                return body.matchedMatchId();
            }
            Thread.sleep(properties.pollMs());
        }
        // Never matched within the bound (likely an odd player out). Leave the queue and stop.
        client.delete().uri("/api/queue/{playerId}", playerId).retrieve().toBodilessEntity();
        return null;
    }

    private MatchView fetchMatch(RestClient client, long matchId) {
        var response = client.get()
                .uri("/api/matches/{id}", matchId)
                .retrieve()
                .toEntity(MatchView.class);
        return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
    }

    private void submitResult(RestClient client, long matchId, long winnerId) {
        client.post()
                .uri("/api/matches/{id}/result", matchId)
                .body(new ResultBody(winnerId))
                .retrieve()
                .toBodilessEntity();
    }

    /** Rating-aware winner: A wins with probability equal to its Elo expected score. */
    private long pickWinner(MatchView match) {
        double pAWins = elo.expectedScore(match.ratingA(), match.ratingB());
        return ThreadLocalRandom.current().nextDouble() < pAWins ? match.playerAId() : match.playerBId();
    }

    private void sleepMatchDuration(SimulationConfig config) throws InterruptedException {
        long min = config.matchDurationMinMs();
        long max = config.matchDurationMaxMs();
        long duration = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        Thread.sleep(duration);
    }

    // Request/response shapes for the HTTP calls. Response views are partial; Spring Boot
    // ignores unknown JSON properties, so they only declare the fields the simulator needs.
    private record JoinBody(long playerId) { }

    private record ResultBody(long winnerId) { }

    private record QueuePollView(String status, Long matchedMatchId) { }

    private record MatchView(long playerAId, long playerBId, int ratingA, int ratingB) { }
}
