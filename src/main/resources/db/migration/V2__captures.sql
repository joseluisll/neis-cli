-- NEIS database schema V2 (US-11.4, issue #69).
-- Webpage captures: one row per capture of a webpage of a registered
-- address. The capture id is DETERMINISTIC (derived from the webpage
-- URL, never from run timestamps), so the same webpage always maps to
-- the same storage folder and the same row. Timestamps are ISO-8601
-- TEXT per the schema's SQLite storage format.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

CREATE TABLE captures (
    address_id TEXT NOT NULL REFERENCES addresses(id),
    capture_id TEXT NOT NULL,
    url        TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (address_id, capture_id)
);
