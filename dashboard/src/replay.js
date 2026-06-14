import { useEffect, useState } from 'react';
import lockingRecording from './replay/locking.json';
import naiveRecording from './replay/naive.json';

// Two real captured runs of the system: a clean LOCKING segment (anomalies stay at zero) and a
// NAIVE segment (the race, anomalies climb, double-match rows). The static demo plays these back so
// no live backend is needed. The strategy toggle picks which recording is playing, so flipping to
// NAIVE still makes the race appear, just sourced from recorded frames instead of a live API.
const RECORDINGS = { locking: lockingRecording, naive: naiveRecording };

/**
 * Drives playback of the recordings. Returns the same data slices the live dashboard polls, plus
 * the toggle/playback controls. Advancing one frame per recorded interval, looping, mirrors the
 * live polling cadence so the panels behave exactly as they do against a real backend.
 */
export function useReplay() {
  const [strategy, setStrategy] = useState('locking');
  const [playing, setPlaying] = useState(true);
  const [frame, setFrame] = useState(0);

  const recording = RECORDINGS[strategy] || RECORDINGS.locking;
  const frames = recording.frames;
  const interval = recording.intervalMs || 1000;

  useEffect(() => {
    if (!playing) return undefined;
    const id = setInterval(() => setFrame((f) => f + 1), interval);
    return () => clearInterval(id);
  }, [playing, interval]);

  // Switching strategy restarts that segment from the top, so LOCKING opens at zero anomalies and
  // NAIVE shows the climb from the beginning.
  const selectStrategy = (next) => {
    setStrategy(next);
    setFrame(0);
  };

  const current = frames[frame % frames.length] || {};
  return {
    stats: current.stats,
    queue: current.queue,
    matches: current.matches,
    anomalies: current.anomalies,
    leaderboard: current.leaderboard,
    strategy,
    playing,
    setStrategy: selectStrategy,
    setPlaying,
  };
}
