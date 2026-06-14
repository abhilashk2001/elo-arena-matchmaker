-- V5__matches_inprogress_index.sql
-- Anomaly detection and the live double-booking gauge look only at *recent* IN_PROGRESS matches,
-- so a long-finished orphaned match (a naive duplicate that never got a result, or a match left
-- open when the simulator stopped) cannot be mistaken for a concurrent double-booking.
--
-- A partial index over just the IN_PROGRESS rows (a small, hot subset of a table that otherwise
-- grows without bound as matches complete) ordered by created_at lets those queries scan a tiny
-- recent range instead of the whole matches table, keeping the stats endpoint inside its budget.
CREATE INDEX idx_matches_inprogress_created ON matches (created_at) WHERE status = 'IN_PROGRESS';
