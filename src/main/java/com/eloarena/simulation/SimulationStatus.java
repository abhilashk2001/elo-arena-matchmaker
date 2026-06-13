package com.eloarena.simulation;

/**
 * The simulator's current state, returned by the start/stop/status endpoints.
 *
 * @param running       whether a simulation is currently active
 * @param activePlayers how many simulated players are still running their loop
 */
public record SimulationStatus(boolean running, int activePlayers) {
}
