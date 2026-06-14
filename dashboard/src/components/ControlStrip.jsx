import { useState } from 'react';

// Drives the demo: start/stop the run and flip the matcher strategy. Presentational, the handlers
// come from the parent so the same strip works in live mode (calls the admin API) and replay mode
// (controls playback). The toggle is the money shot: flipping to NAIVE makes the race appear, then
// LOCKING makes it stop. In replay, start/stop pause and resume playback instead.
export default function ControlStrip({ strategy, playing, onStart, onStop, onStrategy, replay }) {
  const [busy, setBusy] = useState(false);
  const naive = strategy === 'naive';

  async function run(action) {
    setBusy(true);
    try {
      await action();
    } catch (e) {
      // Keep the demo resilient: surface the failure in the console, do not crash the dashboard.
      console.error(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="controls">
      <button className="btn" disabled={busy || (replay && playing)} onClick={() => run(onStart)}>
        ▶ {replay ? 'play' : 'start sim'}
      </button>
      <button className="btn" disabled={busy || (replay && !playing)} onClick={() => run(onStop)}>
        ■ {replay ? 'pause' : 'stop sim'}
      </button>

      <div className="toggle" role="group" aria-label="matcher strategy">
        <button
          className={`toggle-btn ${!naive ? 'active ok' : ''}`}
          disabled={busy}
          onClick={() => run(() => onStrategy('locking'))}
        >
          LOCKING
        </button>
        <button
          className={`toggle-btn ${naive ? 'active warn' : ''}`}
          disabled={busy}
          onClick={() => run(() => onStrategy('naive'))}
        >
          NAIVE
        </button>
      </div>
    </div>
  );
}
