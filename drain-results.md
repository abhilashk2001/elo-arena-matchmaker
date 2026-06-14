# Pairing-throughput scaling (queue drain)

Queue pre-filled with 200000 mutually-compatible waiting players (all rating 1200).
Matcher: batch-size 200, interval 100ms. Window 20s, measured while
the queue stays deep so the matcher, not the offered load, is the bottleneck.

| Matchers | Strategy | Matches in window | Throughput (matches/s) | Speedup vs 1 | Anomalies | Queue left |
|----------|----------|-------------------|------------------------|--------------|-----------|------------|
| 1 | locking | 5000 | 250.0 | 1.0x | 0 | 185400 |
| 2 | locking | 8200 | 410.0 | 1.64x | 0 | 177600 |
| 3 | locking | 9900 | 495.0 | 1.98x | 0 | 176600 |
