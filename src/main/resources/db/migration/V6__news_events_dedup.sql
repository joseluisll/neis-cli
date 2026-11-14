-- NEIS database schema V6 (US-14.5, issue #83).
-- Deduplication across captures: every row carries a DETERMINISTIC
-- canonical_id — derived from the item's stable identifier (its detail
-- URL) or, when the item has no link, from the similarity baseline
-- (kind-agnostic normalized title + leniently parsed date) — so the
-- same story seen again (later captures, other pages, other
-- addresses) lands on the SAME canonical id without any lookup, while
-- each address keeps its own row (the per-address capture evidence).
-- A separate sightings table records EVERY capture in which an item
-- appeared (US-14.2 provenance across captures): one row per
-- (item, saveAll) — re-running a chain adds a sighting, not a record.
-- Pre-V6 rows get no canonical id until their next re-extraction
-- (same policy as the US-14.4 completeness flag).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE news_events ADD COLUMN canonical_id TEXT;

CREATE TABLE news_event_sightings (
    id           TEXT NOT NULL PRIMARY KEY,
    item_id      TEXT NOT NULL REFERENCES news_events(id),
    address_id   TEXT NOT NULL,
    content_id   TEXT,
    execution_id TEXT,
    seen_at      TEXT NOT NULL
);

CREATE INDEX news_event_sightings_item ON news_event_sightings (item_id);
