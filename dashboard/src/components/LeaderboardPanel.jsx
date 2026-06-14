import { useEffect, useRef, useState } from 'react';
import { divisionFor } from '../format.js';

// Top 20 standings. On each poll we diff the new ranks against the previous ones and briefly
// highlight any row that moved, green for a climb, red for a drop. This is the cheap real-time UX
// the PRD asks for: no animation library, just compare two snapshots and flash the delta. The
// previous ranks live in a ref so the diff survives re-renders without driving them.
const FLASH_MS = 1500;

export default function LeaderboardPanel({ entries }) {
  const prevRanks = useRef(new Map());
  const [moves, setMoves] = useState(new Map());
  const rows = entries || [];

  useEffect(() => {
    if (!entries) return;
    const changed = new Map();
    const nextRanks = new Map();
    entries.forEach((e) => {
      nextRanks.set(e.playerId, e.rank);
      const before = prevRanks.current.get(e.playerId);
      if (before != null && before !== e.rank) {
        changed.set(e.playerId, e.rank < before ? 'up' : 'down');
      }
    });
    prevRanks.current = nextRanks;
    if (changed.size > 0) {
      setMoves(changed);
      const id = setTimeout(() => setMoves(new Map()), FLASH_MS);
      return () => clearTimeout(id);
    }
  }, [entries]);

  return (
    <section className="panel leaderboard-panel">
      <h2>LEADERBOARD <span className="dim">top {rows.length}</span></h2>
      <div className="rows">
        <div className="row head">
          <span className="c-rank">#</span>
          <span className="c-handle">player</span>
          <span className="c-rating">rating</span>
        </div>
        {rows.length === 0 && <div className="empty">no ranked players yet</div>}
        {rows.map((e) => {
          const move = moves.get(e.playerId);
          return (
            <div className={`row ${move ? `move-${move}` : ''}`} key={e.playerId}>
              <span className="c-rank">{e.rank}</span>
              <span className="c-handle">
                {e.handle}
                {move === 'up' && <span className="arrow up"> ▲</span>}
                {move === 'down' && <span className="arrow down"> ▼</span>}
              </span>
              <span className="c-rating">
                {e.rating} <span className="div-tag">D{divisionFor(e.rating)}</span>
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
