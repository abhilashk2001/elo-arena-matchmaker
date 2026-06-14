// Small display helpers shared across panels.

export function fmtMs(ms) {
  if (ms == null) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  return `${m}m${String(Math.floor(s % 60)).padStart(2, '0')}s`;
}

export function fmtNum(n, digits = 0) {
  if (n == null) return '-';
  return Number(n).toFixed(digits);
}

// Ten divisions, 200 points each, derived from rating. Mirrors the server's Divisions component;
// used only for the small division tag next to a rating, never for any decision.
export function divisionFor(rating) {
  const fromBottom = Math.floor((rating - 400) / 200);
  const division = 10 - fromBottom;
  return Math.max(1, Math.min(10, division));
}
