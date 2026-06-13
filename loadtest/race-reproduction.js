// Race reproduction under real load. This is the load-test counterpart to the deterministic
// CyclicBarrier unit test: instead of forcing the collision, it generates enough concurrent
// matching pressure that the naive strategy double-matches on its own, then measures the
// anomalies counter.
//
// IMPORTANT: the double-match race only happens with MORE THAN ONE matcher instance running
// against the same database. Bring the stack up with three matcher instances first:
//
//     docker compose up -d --scale app=3
//
// Then run this against the load balancer / any instance:
//
//     STRATEGY=naive   k6 run loadtest/race-reproduction.js   # expect anomalies > 0
//     STRATEGY=locking k6 run loadtest/race-reproduction.js   # expect anomalies == 0
//
// The script flips the strategy, drives a burst of joins, and checks the change in the
// eloarena.anomalies.detected counter against the expectation for that strategy.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, seedPlayers, setStrategy, metricCount, playOneMatch, JSON_HEADERS } from './lib/common.js';

const STRATEGY = __ENV.STRATEGY || 'naive';
const PLAYERS = Number(__ENV.PLAYERS || 5000);

export const options = {
  scenarios: {
    burst: {
      executor: 'per-vu-iterations',
      vus: 200,
      iterations: 5,
      maxDuration: '2m',
    },
  },
};

export function setup() {
  seedPlayers(PLAYERS);
  setStrategy(STRATEGY);
  const baseline = metricCount('eloarena.anomalies.detected');
  return { players: PLAYERS, baseline };
}

export default function (data) {
  const playerId = Math.floor(Math.random() * data.players) + 1;
  playOneMatch(http, check, sleep, playerId);
}

export function teardown(data) {
  // Give the matchers a moment to drain the last claimed batches and run detection.
  sleep(3);
  const after = metricCount('eloarena.anomalies.detected');
  const delta = after - data.baseline;
  console.log(`strategy=${STRATEGY} anomalies_delta=${delta}`);

  if (STRATEGY === 'naive') {
    check(delta, { 'naive records nonzero anomalies (needs >=2 matchers)': (d) => d > 0 });
  } else {
    check(delta, { 'locking records zero anomalies': (d) => d === 0 });
  }
}
