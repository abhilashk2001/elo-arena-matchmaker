# Connection-pool benchmark

Players: 8000 · load window: 60s · in-app simulator (requeue 0.9, match 20-60ms)

| Pool size | Matches created | Acquire wait mean (ms) | Acquire wait max (ms) | Result p99 (ms) | Queue wait p99 (ms) |
|-----------|-----------------|------------------------|-----------------------|-----------------|---------------------|
| 10 | 1742.0 | 121.84 | 1797.0 | 39.78 | 55297.7 |
| 30 | 827.0 | 75.25 | 1963.0 | 129.89 | 59861.11 |
| 50 | 749.0 | 58.66 | 2026.0 | 201.2 | 62243.47 |
