-- NEIS database schema V17 (US-07.6, issue #89).
-- SIMPLIFY side table: the hashed-URL placeholder mapping of the lean
-- tiered rendering (placeholder -> full URL, JSON object) stored with
-- the webpage capture metadata (US-11.4) so the compressed URLs of the
-- simplified HTML5 stay resolvable. NULL = the capture predates the
-- tiered rendering (and a hash-less re-render never wipes a stored
-- table, mirroring the filters/clearing/digest rules of
-- V11/V15/V16).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE captures ADD COLUMN simplify_side_table TEXT;
