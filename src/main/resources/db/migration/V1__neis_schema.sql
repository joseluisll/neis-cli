-- NEIS database schema V1 (US-02.2, issue #16).
-- SQLite3, first iteration. Timestamps are ISO-8601 TEXT (SQLite has no
-- native datetime; ISO TEXT sorts chronologically). Enum-like values are
-- constrained with CHECK constraints.
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

-- Registered addresses (US-03): URL plus listing kind and description.
-- verification_status is typed in the domain when US-04 lands
-- (UNVERIFIED / VERIFIED / FAILED).
CREATE TABLE addresses (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    description         TEXT,
    url                 TEXT NOT NULL,
    type                TEXT NOT NULL CHECK (type IN ('URL_NEWS', 'URL_EVENTS')),
    verification_status TEXT NOT NULL DEFAULT 'UNVERIFIED'
                        CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED', 'FAILED')),
    verified_at         TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

-- Capability flags (US-17): what ANALYZE detected the address listing
-- offers (PAGINATION, FILTERING); a per-address set.
CREATE TABLE address_capabilities (
    address_id TEXT NOT NULL REFERENCES addresses(id),
    capability TEXT NOT NULL,
    PRIMARY KEY (address_id, capability)
);

-- Validated working selectors per address (MVP issue #90): the selector
-- store replaces FileSelectorStoreConnector behind SelectorStorePort.
CREATE TABLE selectors (
    address_id      TEXT PRIMARY KEY REFERENCES addresses(id),
    kind            TEXT NOT NULL,
    selector        TEXT NOT NULL,
    filter_selector TEXT,
    discovered_by   TEXT NOT NULL,
    discovered_at   TEXT NOT NULL,
    notes           TEXT
);

-- Task types (US-05): the executable step kinds (WEBSCRAPE, SIMPLIFY,
-- ANALYZE, FILTER, PRINT, ...).
CREATE TABLE task_types (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT
);

-- Chains of tasks (US-08): an ordered list of tasks, max 10 elements in
-- this iteration, modeled so it can generalize to a DAG (US-08.1).
CREATE TABLE chains (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE chain_tasks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    chain_id        TEXT NOT NULL REFERENCES chains(id),
    position        INTEGER NOT NULL CHECK (position BETWEEN 1 AND 10),
    task_code       TEXT NOT NULL REFERENCES task_types(code),
    parameters_json TEXT,
    UNIQUE (chain_id, position)
);

-- Execution records (US-05.3): every task run leaves a record with
-- status, timestamps and outcome for traceability.
CREATE TABLE executions (
    id           TEXT PRIMARY KEY,
    chain_id     TEXT REFERENCES chains(id),
    task_code    TEXT REFERENCES task_types(code),
    address_id   TEXT REFERENCES addresses(id),
    trigger_type TEXT NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    status       TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    started_at   TEXT,
    finished_at  TEXT,
    outcome      TEXT
);

-- Content records (US-11): every stored content artifact (task output)
-- is recorded; the path is the deterministic filesystem tree location
-- (US-11.4), unique across the content root.
CREATE TABLE content_records (
    id         TEXT PRIMARY KEY,
    address_id TEXT NOT NULL REFERENCES addresses(id),
    capture_id TEXT NOT NULL,
    task_code  TEXT REFERENCES task_types(code),
    kind       TEXT NOT NULL,
    path       TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);
