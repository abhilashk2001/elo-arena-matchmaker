import { useMemo } from 'react';
import { fmtMs } from '../format.js';

// Scrolling feed of recent matches. A row flashes red when its match id shows up in the anomalies
// feed, which is how a double-match becomes unmistakable: under naive, pairs of rows light up red
// as the same player gets booked into two matches at once. The set is rebuilt from both sides of
// each anomaly (the match and the one it conflicts with) so both offending rows flash.
export default function MatchFeed({ matches, anomalies }) {
  const anomalyIds = useMemo(() => {
    const ids = new Set();
    (anomalies || []).forEach((a) => {
      ids.add(a.matchId);
      ids.add(a.conflictingMatchId);
    });
    return ids;
  }, [anomalies]);

  const rows = matches || [];

  return (
    <section className="panel feed-panel">
      <h2>MATCH FEED <span className="dim">latest {rows.length}</span></h2>
      <div className="rows">
        <div className="row head">
          <span className="c-id">#</span>
          <span className="c-pair">match</span>
          <span className="c-delta">Δ</span>
          <span className="c-wait">wait a/b</span>
        </div>
        {rows.length === 0 && <div className="empty">no matches yet</div>}
        {rows.map((m) => {
          const bad = anomalyIds.has(m.id);
          return (
            <div className={`row ${bad ? 'anomaly-row' : ''}`} key={m.id}>
              <span className="c-id">{m.id}</span>
              <span className="c-pair">
                {m.handleA} <span className="dim">({m.ratingA})</span>
                {' vs '}
                {m.handleB} <span className="dim">({m.ratingB})</span>
                {bad && <span className="badge">DOUBLE-MATCH</span>}
              </span>
              <span className="c-delta">{m.ratingDelta}</span>
              <span className="c-wait">{fmtMs(m.waitMsA)} / {fmtMs(m.waitMsB)}</span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
