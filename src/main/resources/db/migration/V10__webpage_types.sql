-- NEIS database schema V10 (US-06.5, issue #71).
-- Webpage type model: every processed webpage is classified into the
-- extensible webpage-type taxonomy (static, spa-<framework>, hybrid,
-- other) and the type is stored with the address it was classified
-- for (the classification point until the US-13.6 preflight) and with
-- every capture recorded from it (US-11.4 metadata). The taxonomy ids
-- are free strings here: the vocabulary lives in the configuration-
-- driven WebpageTypeCatalog (adding a type needs no schema change).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE addresses ADD COLUMN webpage_type TEXT;
ALTER TABLE captures ADD COLUMN webpage_type TEXT;
