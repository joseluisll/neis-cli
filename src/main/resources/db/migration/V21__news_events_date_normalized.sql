-- V21 (US-14.9, issue #123): normalized item dates.
-- A machine-comparable ISO-8601 date column alongside the raw
-- extracted text (kept for audit). Populated at capture time by the
-- store through the ONE shared lenient domain parser
-- (NewsEventQuery.normalizeDate); legacy rows are backfilled by the
-- server-startup normalizer (a pure-SQL backfill cannot run the
-- lenient Java parser without duplicating it — the drift this
-- requirement forbids). NULL means the raw date did not parse (the
-- row is flagged, never dropped or defaulted).
ALTER TABLE news_events ADD COLUMN date_normalized TEXT;
