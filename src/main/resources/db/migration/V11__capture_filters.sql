-- NEIS database schema V11 (US-06.6, issue #73).
-- WEBSCRAPE filters: the applied filter set of a capture — the filter
-- values the task applied BEFORE extraction (URL parameters for
-- static pages, operated controls for rendered pages), recorded with
-- the webpage capture metadata (US-11.4). Stored as a JSON object
-- {"name":"value", ...} in config order; NULL = the capture was
-- fetched without filters (and a re-capture without filters never
-- wipes a stored set, mirroring the webpage_type rule of V10).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE captures ADD COLUMN filters TEXT;
