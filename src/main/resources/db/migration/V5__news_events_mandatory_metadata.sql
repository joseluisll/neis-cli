-- NEIS database schema V5 (US-14.4, issue #74).
-- Minimum mandatory metadata: the store flags every record missing a
-- mandatory field (title, description, date) as incomplete, with the
-- missing fields recorded, so selector or source problems become
-- visible instead of silently storing incomplete items as complete.
-- The optional metadata the source exposes (picture URL, location)
-- is captured when present. Existing rows are complete-by-default
-- (DEFAULT 0): they predate the flag.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE news_events ADD COLUMN picture TEXT;
ALTER TABLE news_events ADD COLUMN location TEXT;
ALTER TABLE news_events ADD COLUMN incomplete INTEGER NOT NULL DEFAULT 0;
ALTER TABLE news_events ADD COLUMN missing_fields TEXT;
