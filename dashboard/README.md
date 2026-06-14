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

## Design notes

- Polling only, no websockets or SSE. Stats refresh every 1s; the queue, match feed, and
  leaderboard every 2s. A dropped or late frame is harmless for a snapshot view, and the read
  endpoints are cheap by design (bounded top-N reads under a 50ms budget).
- No client state library. Two small hooks (`usePoll`, `useClock`) cover everything.
- Display values that involve a rule (the rating band) are computed on the server and rendered
  as-is here, so the band formula is defined once. The band bar animates toward each new
  server value with a CSS transition rather than recomputing the formula on the client.
