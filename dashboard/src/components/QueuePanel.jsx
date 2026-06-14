import { useMemo } from 'react';
import { fmtMs, divisionFor } from '../format.js';

// Full-scale reference for the band bar only (the configured band.max). This is a display constant,
// not the band formula: the actual half-width each player accepts is computed on the server and
// arrives as currentBand. We just draw it as a fraction of full scale.
const BAND_MAX = 400;

// Top waiting players, longest waiter first. Two things move between the 2s polls: the wait timer
// ticks up every second off the shared clock, and the band bar animates toward the latest
// server-computed currentBand via a CSS width transition. The band value itself is never computed
// here, so the expansion rule stays defined in exactly one place (the backend BandPolicy).
export default function QueuePanel({ queue, now }) {
  // Capture when this batch arrived so the live wait timer can advance from the polled baseline.
  const receivedAt = useMemo(() => Date.now(), [queue]);
  const rows = queue || [];

  return (
    <section className="panel queue-panel">
      <h2>QUEUE <span className="dim">top {rows.length} waiting</span></h2>
      <div className="rows">
        <div className="row head">
          <span className="c-handle">player</span>
          <span className="c-rating">rating</span>
          <span className="c-wait">wait</span>
          <span className="c-band">band</span>
        </div>
        {rows.length === 0 && <div className="empty">queue is empty</div>}
        {rows.map((p) => {
          const liveWait = p.waitMs + (now - receivedAt);
          const pct = Math.min(100, (p.currentBand / BAND_MAX) * 100);
          return (
            <div className="row" key={p.playerId}>
              <span className="c-handle">{p.handle}</span>
              <span className="c-rating">
                {p.rating} <span className="div-tag">D{divisionFor(p.rating)}</span>
              </span>
              <span className="c-wait">{fmtMs(liveWait)}</span>
              <span className="c-band">
                <span className="band-bar">
                  <span className="band-fill" style={{ width: `${pct}%` }} />
                </span>
                <span className="band-num">±{p.currentBand}</span>
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
