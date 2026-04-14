-- Phase 11 — Access control: per-channel write permissions
-- allowed_writers: nullable TEXT — comma-separated list of instance IDs and/or
-- capability:tag / role:name patterns. NULL = open (any writer permitted).
ALTER TABLE channel ADD COLUMN allowed_writers TEXT;
