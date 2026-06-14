// Queue storm: a sharp ramp of virtual users all hammering the join/poll/result path at once.
// This is the scenario that pushes the connection pool into contention and makes p99 spike while
// the average stays calm (see story P7.5). Read /actuator/prometheus during the run to watch
// eloarena_queue_wait and hikaricp_connections_acquire.
//
// Run: k6 run loadtest/queue-storm.js
// Tune: BASE_URL, PLAYERS, PEAK_VUS env vars.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, seedPlayers, playOneMatch } from './lib/common.js';

const PLAYERS = Number(__ENV.PLAYERS || 10000);
const PEAK_VUS = Number(__ENV.PEAK_VUS || 500);

export const options = {
  scenarios: {
    storm: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: PEAK_VUS }, // sharp ramp in
        { duration: '1m', target: PEAK_VUS },  // sustained peak
        { duration: '20s', target: 0 },        // ramp down
      ],
    },
  },
  thresholds: {
    // Honest percentiles: the average hides the contention, p99 is where it shows.
    http_req_failed: ['rate<0.02'],
    'http_req_duration{expected_response:true}': ['p(95)<800', 'p(99)<2500'],
  },
};

export function setup() {
  seedPlayers(PLAYERS);
  return { players: PLAYERS };
}

export default function (data) {
  const playerId = Math.floor(Math.random() * data.players) + 1;
  playOneMatch(http, check, sleep, playerId);
}
