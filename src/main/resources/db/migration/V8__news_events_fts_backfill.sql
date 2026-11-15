-- NEIS database schema V8 (US-14.7, issue #85).
-- FTS index backfill: V7 created the news_events_fts index and its
-- consistency triggers, which only cover writes FROM THEN ON — rows
-- that already existed when V7 was applied were never indexed (found
-- live: the kit database's 130+ rows were unsearchable until
-- re-extracted). This migration fills the index from the current
-- repository contents; the WHERE clause keeps it harmless if the index
-- already holds every row.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

INSERT INTO news_events_fts(rowid, title, description, location)
SELECT rowid, title, description, location
FROM news_events
WHERE rowid NOT IN (SELECT rowid FROM news_events_fts);
