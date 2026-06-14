package com.eloarena.dashboard;

/**
 * One waiting player as the dashboard's queue panel renders it.
 *
 * @param playerId    the player's id
 * @param handle      display name
 * @param rating      rating snapshot taken at join time
 * @param waitMs      how long they have been waiting, in milliseconds
 * @param currentBand the player's current accepted rating half-width, computed server-side from the
 *                    matcher's BandPolicy so the band bar the UI draws matches the real pairing rule
 */
public record DashboardQueueEntry(long playerId, String handle, int rating, long waitMs, int currentBand) {
}
