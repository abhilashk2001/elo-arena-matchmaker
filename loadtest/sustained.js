// Sustained loop: a steady arrival rate held for several minutes, the realistic "always-on"
// load the system runs under. Unlike the storm, this keeps the queue at a stable depth so you
// can read steady-state throughput (eloarena_matches_created) and queue wait, not just a spike.
//
// Run: k6 run loadtest/sustained.js
// Tune: BASE_URL, PLAYERS, RATE (iterations/sec), DURATION env vars.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, seedPlayers, playOneMatch } from './lib/common.js';

const PLAYERS = Number(__ENV.PLAYERS || 10000);
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '5m';

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 4),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{expected_response:true}': ['p(95)<500', 'p(99)<1500'],
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
