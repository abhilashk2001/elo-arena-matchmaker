import { usePoll, useClock } from './hooks.js';
import { fetchStats, fetchQueue, fetchMatches, fetchAnomalies, fetchLeaderboard } from './api.js';
import StatsStrip from './components/StatsStrip.jsx';
import ControlStrip from './components/ControlStrip.jsx';
import QueuePanel from './components/QueuePanel.jsx';
import MatchFeed from './components/MatchFeed.jsx';
import LeaderboardPanel from './components/LeaderboardPanel.jsx';

// Poll cadences from the PRD: stats is the headline strip so it refreshes fastest; the feeds and
// standings move slower and are heavier, so they poll every two seconds.
const STATS_MS = 1000;
const FEED_MS = 2000;

export default function App() {
  const now = useClock();

  const { data: stats } = usePoll(fetchStats, STATS_MS);
  const { data: queue } = usePoll(fetchQueue, FEED_MS);
  const { data: matches } = usePoll(fetchMatches, FEED_MS);
  const { data: anomalies } = usePoll(fetchAnomalies, FEED_MS);
  const { data: leaderboard } = usePoll(fetchLeaderboard, FEED_MS);

  return (
    <div className="app">
      <header className="topbar">
        <span className="title">ELO ARENA <span className="dim">// matchmaker ops</span></span>
        <ControlStrip strategy={stats?.currentStrategy} />
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
