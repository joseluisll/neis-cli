-- US-18.2 (issue #117): the REMOTE trigger. The executions table's V1
-- CHECK constraint lists only ('MANUAL', 'SCHEDULED'), so a
-- remote-triggered run would be rejected at write time — on the real
-- store, invisible to in-memory test doubles (the US-11 FK lesson).
-- SQLite cannot ALTER a CHECK constraint: rebuild the table with the
-- widened vocabulary, carrying every existing column of V1+V12.
--
-- No other table references executions(id) (V14's content_records
-- execution_id is an UNENFORCED reference — a plain TEXT column), so
-- a table rebuild touches no dependent rows.

CREATE TABLE executions_new (
    id           TEXT PRIMARY KEY,
    chain_id     TEXT REFERENCES chains(id),
    task_code    TEXT REFERENCES task_types(code),
    address_id   TEXT REFERENCES addresses(id),
    trigger_type TEXT NOT NULL CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'REMOTE')),
    status       TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    started_at   TEXT,
    finished_at  TEXT,
    outcome      TEXT,
    parameters   TEXT
);

INSERT INTO executions_new
    (id, chain_id, task_code, address_id, trigger_type, status,
     started_at, finished_at, outcome, parameters)
SELECT id, chain_id, task_code, address_id, trigger_type, status,
       started_at, finished_at, outcome, parameters
FROM executions;

DROP TABLE executions;
ALTER TABLE executions_new RENAME TO executions;
