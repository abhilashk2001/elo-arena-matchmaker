// Thin wrappers over the backend endpoints. The dashboard is a pure reader plus a few control
// actions, so there is no client state library here, just fetch.

async function getJson(path) {
  const res = await fetch(path, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${path} -> ${res.status}`);
  }
  return res.json();
}

// Read endpoints the panels poll.
export const fetchQueue = () => getJson('/dashboard/queue');
export const fetchMatches = () => getJson('/dashboard/matches');
export const fetchStats = () => getJson('/dashboard/stats');
export const fetchAnomalies = () => getJson('/dashboard/anomalies');
export const fetchLeaderboard = () => getJson('/api/leaderboard?limit=20');

// Control actions wired to the control strip.
async function post(path, body) {
  const res = await fetch(path, {
    method: body === undefined ? 'POST' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(`${path} -> ${res.status}`);
  }
  return res.json().catch(() => ({}));
}

export const startSimulation = (players) =>
  post('/api/admin/simulate/start', {
    players,
    requeueProbability: 0.9,
    matchDurationMinMs: 20,
    matchDurationMaxMs: 60,
  });

export const stopSimulation = () => post('/api/admin/simulate/stop');

// The strategy toggle uses PUT, per the Phase 4 admin endpoint.
export const setStrategy = async (strategy) => {
  const res = await fetch('/api/admin/matcher-strategy', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ strategy }),
  });
  if (!res.ok) {
    throw new Error(`set strategy -> ${res.status}`);
  }
  return res.json();
};
