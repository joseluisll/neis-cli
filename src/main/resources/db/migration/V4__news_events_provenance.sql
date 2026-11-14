-- NEIS database schema V4 (US-14.2, issue #56).
-- Provenance linking: every stored news/event record references the
-- execution that extracted it and the scrape.html content record it
-- came from, so the origin of every item is traceable. Both columns
-- are nullable (records extracted before this migration, and test
-- flows without an execution, have no provenance).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE news_events ADD COLUMN execution_id TEXT;
ALTER TABLE news_events ADD COLUMN content_id TEXT;
