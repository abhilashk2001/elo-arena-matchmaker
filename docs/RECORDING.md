# Recording the dashboard demo (GIF + 60s clip)

The README's centerpiece is an animated GIF of the matcher strategy being flipped from LOCKING to
NAIVE under load, with red double-match rows appearing, then flipping back. This is a human
screen-recording step. Follow this to capture it.

The README references `docs/demo.gif`. Save the final GIF there.

## Setup

1. Bring the stack up with a few matchers so the race collides hard:

   ```
   docker compose --profile matchers up -d --build --scale matcher=3
   ```

2. Open the dashboard at http://localhost:3000 and make the window dense (the panels are designed
   for a wide, dark screen). Give it a moment so the seeded 1,000 players are present.

## The sequence to record (about 45-60 seconds)

1. Click **start sim**. Watch the queue panel fill and the match feed start scrolling. The anomaly
   counter reads **0** under LOCKING. Let it run ~10s so the feed is clearly moving.
2. Flip the strategy toggle to **NAIVE**. Within a few seconds, red `DOUBLE-MATCH` rows appear in
   the match feed and the big anomaly counter starts climbing. Hold here ~15s so the red is
   unmistakable and the counter is clearly rising.
3. Flip back to **LOCKING**. New anomalies stop appearing (the counter stops climbing). Hold ~10s.
4. Click **stop sim**.

That LOCKING(0) -> NAIVE(red, climbing) -> LOCKING(stops) arc is the whole story.

## Capturing

- **macOS:** `Cmd+Shift+5` to record a region (the dashboard window) to a `.mov`.
- Convert to a GIF. With ffmpeg + gifski (best quality):

  ```
  ffmpeg -i demo.mov -vf "fps=12,scale=1280:-1:flags=lanczos" -f yuv4mpegpipe - \
    | gifski -o docs/demo.gif --fps 12 --width 1280 -
  ```

  Or a one-liner with just ffmpeg (larger file):

  ```
  ffmpeg -i demo.mov -vf "fps=12,scale=1280:-1:flags=lanczos" docs/demo.gif
  ```

- Keep the GIF under ~10 MB so it loads on GitHub. Trim to the essential arc; 12 fps is plenty.
- Also keep the full-resolution `.mov` as the 60-second screen recording deliverable (it does not
  need to live in the repo; link it or attach it to the portfolio).

## Sanity check

Open the README locally (or on GitHub) and confirm the GIF renders at the top and the
LOCKING -> NAIVE -> LOCKING transition is legible at the chosen size.
