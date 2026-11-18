-- NEIS database schema V18 (US-13.6, issue #65; US-13.2, issue #51).
-- Address preflight capture: the lightweight entry-page probe's result
-- (HTTP status, content type, size, shell indicators, rendered-probe
-- usage, webpage-type classification and the estimated retrieval
-- connector) stored as a JSON object with the webpage capture metadata
-- (US-11.4) so every preflight leaves its evidence trail. NULL = the
-- capture predates the preflight (and a re-preflight never wipes a
-- stored record, mirroring the filters/cleared/digest/side-table rules
-- of V11/V15/V16/V17).
--
-- AI-task classification (US-13.2): the AI task's classification of the
-- processed content (news/event/other, with the identified selector and
-- its source) recorded as a JSON object on the capture metadata of the
-- run. NULL = no AI-task classification recorded for the capture.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE captures ADD COLUMN preflight TEXT;
ALTER TABLE captures ADD COLUMN classification TEXT;
