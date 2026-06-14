# Elo Arena ops dashboard

A single-page React + Vite ops view for the matchmaker. It polls the backend read endpoints and
renders a dense mission-control screen: a stats strip, the live queue with band bars, the match
feed, and the leaderboard, plus a control strip to drive the simulator and flip the matcher
strategy.

The point of the dashboard is to make the race condition visible. Start the simulator, flip the
strategy to NAIVE under load, and red double-match rows appear in the match feed within seconds
while the anomaly counter climbs. Flip back to LOCKING and they stop.

## Running

The backend must be up first (see the repo root README and `docker-compose.yml`). It serves the
API on `:8080`.

```
cd dashboard
npm install
npm run dev
```

Vite serves the dashboard on `:5173` and proxies `/dashboard`, `/api`, and `/actuator` to the
backend on `:8080`. Point at a different backend with `ELOARENA_API_URL`.

```
npm run build    # production build into dist/
npm run preview  # serve the built bundle
```

## Replay mode (the hosted demo)

The public demo at GitHub Pages is a **static, backendless** build. Rather than pay for an always-on
server, it replays two real captured runs of the system: a clean LOCKING segment (anomalies stay at
zero) and a NAIVE segment (the race). The LOCKING/NAIVE toggle switches between the recordings, so
the money shot still works; start/stop becomes pause/play, and a REPLAY badge marks it as a
recording.

- Recordings live in `src/replay/*.json`, captured from a running stack by `scripts/capture-demo.sh`.
- Build the replay version with `VITE_REPLAY=1 npm run build`. Without the flag the build is the live
  dashboard that polls a real backend (what the compose/nginx image serves).
- The same components render in both modes; only the data source differs (`src/replay.js` vs the
  polling hooks in `src/hooks.js`).
- GitHub Pages deploy is automated in `.github/workflows/deploy-dashboard.yml` (builds with
  `VITE_REPLAY=1` and the project-page base path).

To refresh the recordings: bring the stack up (`docker compose --profile matchers up -d --scale
matcher=3` for a strong NAIVE signal), run `scripts/capture-demo.sh`, and rebuild.

## Design notes

- Polling only, no websockets or SSE. Stats refresh every 1s; the queue, match feed, and
  leaderboard every 2s. A dropped or late frame is harmless for a snapshot view, and the read
  endpoints are cheap by design (bounded top-N reads under a 50ms budget).
- No client state library. Two small hooks (`usePoll`, `useClock`) cover everything.
- Display values that involve a rule (the rating band) are computed on the server and rendered
  as-is here, so the band formula is defined once. The band bar animates toward each new
  server value with a CSS transition rather than recomputing the formula on the client.
