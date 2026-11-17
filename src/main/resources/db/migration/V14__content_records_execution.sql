-- NEIS database schema V14 (US-16.6, issue #102).
-- Run-scoped content attribution: content_records gains a nullable
-- reference to the execution that produced it. Nullable BY DESIGN —
-- pre-existing records (and artifacts written outside a run, e.g. by
-- maintenance paths) stay address-scoped and must keep rendering.
-- The record id stays deterministic (address+capture+task+file), so a
-- re-run UPDATES the same row and the execution reference follows the
-- latest producing run.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE content_records ADD COLUMN execution_id TEXT;

CREATE INDEX idx_content_records_execution
    ON content_records(execution_id);
