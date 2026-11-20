-- US-14.10 (issue #124): manual management of stored news/event items —
-- correction and pruning. A mis-extracted item (wrong title, date or
-- kind) or a false positive can be corrected or pruned by the
-- operator, traceably.
--
-- pruned / pruned_at / prune_reason: the soft-delete of a false
-- positive. Pruned rows stay in the table (provenance history remains
-- inspectable) but are excluded from list, search, export and the
-- repository counts.
--
-- corrections: the correction trace, a JSON array of
--   {"at": <iso-instant>, "by": <actor>, "note": <note or null>,
--    "fields": {"<field>": ["<old>", "<new>"], ...}}
-- one entry per correction, appended — never a silent overwrite.
ALTER TABLE news_events ADD COLUMN pruned INTEGER NOT NULL DEFAULT 0;
ALTER TABLE news_events ADD COLUMN pruned_at TEXT;
ALTER TABLE news_events ADD COLUMN prune_reason TEXT;
ALTER TABLE news_events ADD COLUMN corrections TEXT;
