# Dashboard endpoint latency

Working set: 10000 players, 10000 waiting in queue, 50000 matches, 300 anomalies.
Measured at rest (no simulator, matcher loop off), 200 samples per endpoint.
Latency is full client-observed time_total. Budget: under 50ms.

| Endpoint | p50 (ms) | p99 (ms) | Budget |
|----------|----------|----------|--------|
| `/dashboard/queue` | 2.9 | 7.8 | OK |
| `/dashboard/matches` | 3.9 | 17.3 | OK |
| `/dashboard/stats` | 4.0 | 10.8 | OK |
| `/dashboard/anomalies` | 2.8 | 6.0 | OK |
