import { useState } from 'react';
import { startSimulation, stopSimulation, setStrategy } from '../api.js';

// Drives the demo from the UI: start/stop the simulator and flip the matcher strategy. The toggle
// is the money shot. With load running, flipping to NAIVE makes red anomaly rows appear within
// seconds, and flipping back to LOCKING stops them. The current strategy is read from the stats
// poll (single source of truth on the server), so the toggle reflects reality even if someone
// flipped it via the API.
const SIM_PLAYERS = 1000;

export default function ControlStrip({ strategy }) {
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
      <button className="btn" disabled={busy} onClick={() => run(() => startSimulation(SIM_PLAYERS))}>
        ▶ start sim
      </button>
      <button className="btn" disabled={busy} onClick={() => run(() => stopSimulation())}>
        ■ stop sim
      </button>

      <div className="toggle" role="group" aria-label="matcher strategy">
        <button
          className={`toggle-btn ${!naive ? 'active ok' : ''}`}
          disabled={busy}
          onClick={() => run(() => setStrategy('locking'))}
        >
          LOCKING
        </button>
        <button
          className={`toggle-btn ${naive ? 'active warn' : ''}`}
          disabled={busy}
          onClick={() => run(() => setStrategy('naive'))}
        >
          NAIVE
        </button>
      </div>
    </div>
  );
}
