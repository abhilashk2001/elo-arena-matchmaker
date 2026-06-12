-- V1__init.sql
-- Initial schema for Elo Arena Matchmaker.
--
-- Postgres is the source of truth. Every later schema change is a new versioned
-- migration (V2__..., V3__...); we never edit an applied migration and never let
-- Hibernate manage DDL. Identity columns use BIGINT GENERATED ALWAYS AS IDENTITY and
-- all timestamps are TIMESTAMPTZ (timezone-aware).

-- Players ---------------------------------------------------------------------
CREATE TABLE players (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle          TEXT NOT NULL UNIQUE,          -- generated name, e.g. "CrimsonFox_4821"
    rating          INTEGER NOT NULL DEFAULT 1200,
    region          TEXT NOT NULL DEFAULT 'NA',    -- NA / EU / APAC (cosmetic for now)
    games_played    INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seasons ---------------------------------------------------------------------
CREATE TABLE seasons (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT NOT NULL,                 -- "Season 1"
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ                    -- NULL means active
);

-- Enforce "at most one active season" at the database level.
-- One option is an exclusion constraint: EXCLUDE USING gist ((1) WITH =) WHERE (ended_at IS NULL),
-- but that needs the btree_gist extension. A partial unique index on a constant key is simpler
-- and needs no extension: every active row shares the key value TRUE, and UNIQUE forbids a second.
CREATE UNIQUE INDEX uq_one_active_season ON seasons ((TRUE)) WHERE ended_at IS NULL;

-- Queue entries ---------------------------------------------------------------
CREATE TABLE queue_entries (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id        BIGINT NOT NULL REFERENCES players(id),
    rating_at_join   INTEGER NOT NULL,             -- denormalized snapshot of the player's rating
    enqueued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    status           TEXT NOT NULL DEFAULT 'WAITING',
    matched_match_id BIGINT,                        -- set when matched
    CONSTRAINT chk_queue_status CHECK (status IN ('WAITING', 'MATCHED', 'CANCELLED'))
);

-- rating_at_join is denormalized on purpose: the pairing query must not join back to
-- players on the hot path. We accept that a player's live rating can drift from this
-- snapshot while they wait; the snapshot is the rating they queued at.

-- Enforce "one active (WAITING) queue entry per player".
-- A plain UNIQUE (player_id, status) would be WRONG: it would also forbid a player from
-- having two historical rows with the same status (for example two CANCELLED entries),
-- blocking normal history. Scoping the uniqueness to only WAITING rows fixes that.
CREATE UNIQUE INDEX uq_player_waiting ON queue_entries (player_id) WHERE status = 'WAITING';

-- Hot-path index for the pairing query, which scans WAITING rows ordered by rating and age.
CREATE INDEX idx_queue_waiting_rating ON queue_entries (rating_at_join, enqueued_at) WHERE status = 'WAITING';

-- Matches ---------------------------------------------------------------------
CREATE TABLE matches (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_id       BIGINT NOT NULL REFERENCES seasons(id),
    player_a_id     BIGINT NOT NULL REFERENCES players(id),
    player_b_id     BIGINT NOT NULL REFERENCES players(id),
    rating_a        INTEGER NOT NULL,              -- ratings captured at match creation
    rating_b        INTEGER NOT NULL,
    rating_delta    INTEGER NOT NULL,              -- abs(rating_a - rating_b), a band-quality metric
    wait_ms_a       BIGINT NOT NULL,               -- how long each player waited (band-expansion metric)
    wait_ms_b       BIGINT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    winner_id       BIGINT REFERENCES players(id), -- NULL until completed
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_players_distinct CHECK (player_a_id <> player_b_id),
    CONSTRAINT chk_match_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_matches_recent ON matches (created_at DESC);
CREATE INDEX idx_matches_status ON matches (status) WHERE status = 'IN_PROGRESS';

-- Rating history (append-only audit table) ------------------------------------
CREATE TABLE rating_history (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id       BIGINT NOT NULL REFERENCES players(id),
    match_id        BIGINT NOT NULL REFERENCES matches(id),
    season_id       BIGINT NOT NULL REFERENCES seasons(id),
    rating_before   INTEGER NOT NULL,
    rating_after    INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Database-level idempotency backstop: even if application logic is bypassed by a race,
    -- the DB rejects a second rating application for the same player and match.
    CONSTRAINT uq_rating_once_per_match UNIQUE (player_id, match_id)
);
-- rating_history is append-only. We never UPDATE these rows; the running rating lives on
-- players.rating and the per-match deltas are recorded here for audit and history.

-- Season leaderboard snapshots ------------------------------------------------
CREATE TABLE season_leaderboard_snapshots (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_id       BIGINT NOT NULL REFERENCES seasons(id),
    player_id       BIGINT NOT NULL REFERENCES players(id),
    final_rating    INTEGER NOT NULL,
    final_rank      INTEGER NOT NULL,
    games_played    INTEGER NOT NULL,
    snapshot_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_snapshot UNIQUE (season_id, player_id)
);
