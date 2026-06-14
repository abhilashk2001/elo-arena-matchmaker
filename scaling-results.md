# Matcher-scaling benchmark

Window: 30s · api-only instance runs the simulator (requeue 0.95, match 20-60ms);
N matcher-only instances do the pairing. Throughput is the shared Redis matches counter.

| Players | Matchers | Strategy | Matches in window | Throughput (matches/s) | Anomalies |
|---------|----------|----------|-------------------|------------------------|-----------|
| 10000 | 1 | locking | 681 | 22.7 | 0 |
| 10000 | 2 | locking | 1152 | 38.4 | 0 |
| 10000 | 3 | locking | 903 | 30.1 | 0 |
| 10000 | 3 | naive | 117 | 3.9 | 840 |
| 50000 | 3 | locking | 922 | 30.7 | 0 |
