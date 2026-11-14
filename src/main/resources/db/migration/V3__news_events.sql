-- NEIS database schema V3 (US-14.1, issue #55).
-- The news/events repository: one row per extracted news/event item,
-- with all the metadata WEBSCRAPE can extract. The id is DETERMINISTIC
-- (derived from the address and the item's detail URL — or title when
-- the item has no link), so re-extraction UPSERTS the same row instead
-- of duplicating it (US-15.4 idempotency). One record per
-- (address, url): re-running WEBSCRAPE over the same webpage updates
-- the same rows. Timestamps are ISO-8601 TEXT per the schema's SQLite
-- storage format.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

CREATE TABLE news_events (
    id          TEXT NOT NULL PRIMARY KEY,
    address_id  TEXT NOT NULL REFERENCES addresses(id),
    kind        TEXT NOT NULL,
    title       TEXT NOT NULL,
    description TEXT,
    date        TEXT,
    url         TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE UNIQUE INDEX news_events_address_item
    ON news_events (address_id, COALESCE(url, 'title:' || title));
