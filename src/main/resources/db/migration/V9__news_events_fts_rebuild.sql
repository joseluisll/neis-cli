-- NEIS database schema V9 (US-14.7, issue #85).
-- Corrects the V8 backfill: the INSERT...SELECT approach was a NO-OP —
-- for an EXTERNAL-CONTENT FTS5 table, `SELECT rowid FROM
-- news_events_fts` is answered from the CONTENT table (news_events),
-- so V8's `WHERE rowid NOT IN (...)` excluded every row (found live:
-- the kit database stayed unsearchable after V8). The documented way
-- to build an external-content index from its content table is the
-- 'rebuild' command; it is idempotent (a full rebuild from the current
-- content), so this migration is safe to re-apply semantics on any
-- database state.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

INSERT INTO news_events_fts(news_events_fts) VALUES ('rebuild');
