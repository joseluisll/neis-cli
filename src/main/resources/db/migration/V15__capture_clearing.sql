-- NEIS database schema V15 (US-06.7, issue #75).
-- Obstruction clearing: the removal record of a capture — what the
-- clearing (US-06.7) removed from the captured HTML5 BEFORE storage,
-- so the stored HTML5's provenance states what is no longer in it
-- (US-11.4 metadata). Stored as a JSON object
-- {"removed": <count>, "rules": ["<rule id>", ...]}; NULL = the
-- capture was stored without clearing (and a clearing-less
-- re-capture never wipes a stored record, mirroring the filters rule
-- of V11).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE captures ADD COLUMN cleared TEXT;
