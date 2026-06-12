-- V3__seed_initial_season.sql
-- Seed the first active season so the matcher always has a season to stamp matches with.
-- Phase 6's season rollover keeps exactly one active season from here on.
INSERT INTO seasons (name) VALUES ('Season 1');
