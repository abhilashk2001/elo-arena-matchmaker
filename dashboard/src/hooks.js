import { useEffect, useRef, useState } from 'react';

/**
 * Poll a fetcher on an interval and expose its latest value. This is the whole "real-time" story:
 * no websockets, no SSE, just a timer per panel. Polling is enough because the data is a snapshot
 * view, a dropped or late frame is harmless, and the read endpoints are cheap by design.
 *
 * The in-flight guard skips a tick if the previous request has not returned, so a slow backend
 * can never stack up overlapping requests. A stale-response guard drops a reply that arrives after
 * the component unmounted.
 */
export function usePoll(fetcher, intervalMs, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const inFlight = useRef(false);

  useEffect(() => {
    let alive = true;

    async function tick() {
      if (inFlight.current) return;
      inFlight.current = true;
      try {
        const next = await fetcher();
        if (alive) {
          setData(next);
          setError(null);
        }
      } catch (e) {
        if (alive) setError(e);
      } finally {
        inFlight.current = false;
      }
    }

    tick();
    const id = setInterval(tick, intervalMs);
    return () => {
      alive = false;
      clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, error };
}

/**
 * A monotonically increasing "now" that re-renders every second. Panels use it to advance live
 * wait timers between polls, so a player's wait time ticks up smoothly even though the queue is
 * only refetched every two seconds.
 */
export function useClock(intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
