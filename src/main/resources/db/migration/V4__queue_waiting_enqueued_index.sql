-- V4__queue_waiting_enqueued_index.sql
-- Hot-path index for the locking matcher's claim query, which filters status='WAITING'
-- and orders by enqueued_at. The V1 index idx_queue_waiting_rating leads with rating_at_join,
-- so it cannot serve an ORDER BY enqueued_at without a sort. This partial index on enqueued_at
-- lets the claim walk WAITING rows in wait-time order and take the first N with no sort step.
CREATE INDEX idx_queue_waiting_enqueued ON queue_entries (enqueued_at) WHERE status = 'WAITING';
