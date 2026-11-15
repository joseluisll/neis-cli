-- NEIS database schema V7 (US-14.7, issue #85).
-- Full-text search over the repository: an FTS5 EXTERNAL-CONTENT index
-- over the news_events textual metadata (title, description, location)
-- kept consistent with the repository by triggers — every INSERT /
-- UPDATE / DELETE on news_events updates the index in the same
-- statement, so new, updated and removed records are always reflected.
-- The index is storage-level: it carries no data that news_events does
-- not have, and rows are reachable through their SQLite rowid.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

CREATE VIRTUAL TABLE news_events_fts USING fts5(
    title,
    description,
    location,
    content='news_events',
    content_rowid='rowid'
);

CREATE TRIGGER news_events_fts_ai AFTER INSERT ON news_events BEGIN
    INSERT INTO news_events_fts(rowid, title, description, location)
    VALUES (new.rowid, new.title, new.description, new.location);
END;

CREATE TRIGGER news_events_fts_ad AFTER DELETE ON news_events BEGIN
    INSERT INTO news_events_fts(news_events_fts, rowid, title, description, location)
    VALUES ('delete', old.rowid, old.title, old.description, old.location);
END;

CREATE TRIGGER news_events_fts_au AFTER UPDATE ON news_events BEGIN
    INSERT INTO news_events_fts(news_events_fts, rowid, title, description, location)
    VALUES ('delete', old.rowid, old.title, old.description, old.location);
    INSERT INTO news_events_fts(rowid, title, description, location)
    VALUES (new.rowid, new.title, new.description, new.location);
END;
