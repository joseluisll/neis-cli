-- V13 (US-16.5, issue #97 review finding): persisted configuration
-- overrides. The management UI can EDIT a small allowlist of runtime-
-- safe settings; each edit is validated before saving and stored here,
-- layered over application.yml (the YAML stays the source of record;
-- the override wins). Applied at every boot after the consumers are
-- wired, and immediately on edit from the management UI.
CREATE TABLE settings (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);
