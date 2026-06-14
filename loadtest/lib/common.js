// Shared helpers for the k6 scenarios. All scripts drive the real HTTP API, the same one the
// in-app simulator uses, so the load exercises the whole stack.

import http from 'k6/http';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// Seed a deterministic player population. Safe to call repeatedly; the seeder is idempotent
// for a given seed.
export function seedPlayers(count, seed) {
  return http.post(`${BASE}/api/admin/seed?count=${count}&seed=${seed || 42}`);
}

// Switch the active matcher strategy ('locking' or 'naive').
export function setStrategy(strategy) {
  return http.put(`${BASE}/api/admin/matcher-strategy`, JSON.stringify({ strategy }), JSON_HEADERS);
}

// Read a single-value Micrometer COUNT metric (e.g. anomalies). Returns 0 if absent.
export function metricCount(name) {
  const res = http.get(`${BASE}/actuator/metrics/${name}`);
  if (res.status !== 200) {
    return 0;
  }
  const measurements = res.json('measurements') || [];
  const count = measurements.find((m) => m.statistic === 'COUNT');
  return count ? count.value : 0;
}

// One player's lifecycle over HTTP: join, poll until matched (bounded), then submit a result.
// Returns true if a result was submitted.
export function playOneMatch(http, check, sleep, playerId, maxPolls) {
  const join = http.post(`${BASE}/api/queue/join`, JSON.stringify({ playerId }), JSON_HEADERS);
  check(join, { 'joined or already queued': (r) => r.status === 201 || r.status === 409 });

  for (let i = 0; i < (maxPolls || 15); i++) {
    const status = http.get(`${BASE}/api/queue/${playerId}`);
    if (status.status === 200 && status.json('status') === 'MATCHED') {
      const matchId = status.json('matchedMatchId');
      const match = http.get(`${BASE}/api/matches/${matchId}`);
      if (match.status === 200) {
        const winnerId = Math.random() < 0.5 ? match.json('playerAId') : match.json('playerBId');
        http.post(`${BASE}/api/matches/${matchId}/result`, JSON.stringify({ winnerId }), JSON_HEADERS);
        return true;
      }
      return false;
    }
    sleep(0.2);
  }
  // Never matched in time: leave so we do not leak a WAITING entry.
  http.del(`${BASE}/api/queue/${playerId}`);
  return false;
}
