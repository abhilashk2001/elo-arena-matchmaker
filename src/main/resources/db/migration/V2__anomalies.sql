-- V2__anomalies.sql
-- Observability log for detected double-matches: a player found in more than one
-- IN_PROGRESS match. This is a measurement artifact, not a source of truth, so it has no
-- foreign keys (the detector must never block or fail match creation).

CREATE TABLE anomalies (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id            BIGINT NOT NULL,
    match_id             BIGINT NOT NULL,      -- the match whose creation revealed the conflict
    conflicting_match_id BIGINT NOT NULL,      -- the other IN_PROGRESS match the player is in
    detected_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_anomalies_detected ON anomalies (detected_at DESC);
