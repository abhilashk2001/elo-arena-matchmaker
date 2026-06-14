import { fmtMs, fmtNum } from '../format.js';

// The glanceable health strip. The anomaly counter is the demo's headline: it reads zero under
// locking and climbs the instant the strategy is flipped to naive under load, so it gets the big
// treatment and turns red when non-zero.
export default function StatsStrip({ stats }) {
  const s = stats || {};
  const anomalies = s.anomalyCount ?? 0;

  return (
    <section className="stats-strip">
      <Stat label="queue depth" value={fmtNum(s.queueDepth)} />
      <Stat label="matches/sec" value={fmtNum(s.matchesPerSec, 1)} />
      <Stat label="avg wait" value={s.avgWaitMs == null ? '-' : fmtMs(s.avgWaitMs)} />
      <Stat label="p99 pairing" value={s.p99PairingLatencyMs == null ? '-' : fmtMs(s.p99PairingLatencyMs)} />
      <Stat label="matchers" value={fmtNum(s.activeMatcherCount)} />
      <Stat label="strategy" value={(s.currentStrategy || '-').toUpperCase()}
            tone={s.currentStrategy === 'naive' ? 'warn' : 'ok'} />
      <div className={`stat anomaly ${anomalies > 0 ? 'bad' : 'ok'}`}>
        <div className="stat-value big">{fmtNum(anomalies)}</div>
        <div className="stat-label">anomalies</div>
      </div>
    </section>
  );
}

function Stat({ label, value, tone }) {
  return (
    <div className={`stat ${tone || ''}`}>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}
