import { usePoll, useClock } from './hooks.js';
import { useReplay } from './replay.js';
import {
  fetchStats, fetchQueue, fetchMatches, fetchAnomalies, fetchLeaderboard,
  startSimulation, stopSimulation, setStrategy as apiSetStrategy,
} from './api.js';
import StatsStrip from './components/StatsStrip.jsx';
import ControlStrip from './components/ControlStrip.jsx';
import QueuePanel from './components/QueuePanel.jsx';
import MatchFeed from './components/MatchFeed.jsx';
import LeaderboardPanel from './components/LeaderboardPanel.jsx';

// Replay mode is selected at build time (VITE_REPLAY=1) for the static, backendless deploy. The
// same components render in both modes; only the data source and the control wiring differ.
const REPLAY = import.meta.env.VITE_REPLAY === '1';

// Poll cadences from the PRD: stats is the headline strip so it refreshes fastest; the feeds and
// standings move slower and are heavier, so they poll every two seconds.
const STATS_MS = 1000;
const FEED_MS = 2000;
const SIM_PLAYERS = 1000;

export default function App() {
  return REPLAY ? <ReplayApp /> : <LiveApp />;
}

// Shared layout. Whatever produced the data (live polling or recorded playback), the panels are
// identical, so both modes funnel through here.
function Shell({ replay, stats, queue, matches, anomalies, leaderboard, controls, now }) {
  return (
    <div className="app">
      <header className="topbar">
        <span className="title">
          ELO ARENA <span className="dim">// matchmaker ops</span>
          {replay && <span className="replay-badge" title="Playing back a recorded run of the real system">REPLAY</span>}
        </span>
        {controls}
      </header>

      <StatsStrip stats={stats} />

      <main className="grid">
        <QueuePanel queue={queue} now={now} />
        <MatchFeed matches={matches} anomalies={anomalies} now={now} />
        <LeaderboardPanel entries={leaderboard} />
      </main>
    </div>
  );
}

// Live mode: poll the backend and drive it via the admin API.
function LiveApp() {
  const now = useClock();
  const { data: stats } = usePoll(fetchStats, STATS_MS);
  const { data: queue } = usePoll(fetchQueue, FEED_MS);
  const { data: matches } = usePoll(fetchMatches, FEED_MS);
  const { data: anomalies } = usePoll(fetchAnomalies, FEED_MS);
  const { data: leaderboard } = usePoll(fetchLeaderboard, FEED_MS);

  const controls = (
    <ControlStrip
      strategy={stats?.currentStrategy}
      onStart={() => startSimulation(SIM_PLAYERS)}
      onStop={() => stopSimulation()}
      onStrategy={(s) => apiSetStrategy(s)}
    />
  );
  return (
    <Shell stats={stats} queue={queue} matches={matches} anomalies={anomalies}
           leaderboard={leaderboard} controls={controls} now={now} />
  );
}

// Replay mode: play back the recorded segments; the toggle switches between them, start/stop is
// pause/resume. No network.
function ReplayApp() {
  const now = useClock();
  const r = useReplay();

  const controls = (
    <ControlStrip
      replay
      strategy={r.strategy}
      playing={r.playing}
      onStart={() => r.setPlaying(true)}
      onStop={() => r.setPlaying(false)}
      onStrategy={(s) => r.setStrategy(s)}
    />
  );
  return (
    <Shell replay stats={r.stats} queue={r.queue} matches={r.matches} anomalies={r.anomalies}
           leaderboard={r.leaderboard} controls={controls} now={now} />
  );
}
