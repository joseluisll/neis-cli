-- US-18.2 (issue #117): remote jobs — the persisted asynchronous
-- envelope of long-running remote invocations. The job WRAPS the
-- execution layer (execution_id links the produced execution record)
-- and never duplicates it.
--
-- Deliberately NO foreign keys: a job is an AUDIT record of a remote
-- invocation. It must survive an address removal (the invocation
-- happened even if the address is gone since), and executions are
-- never deleted but a defensive FK would couple two audit trails.
CREATE TABLE remote_jobs (
    id TEXT PRIMARY KEY,
    operation TEXT NOT NULL,
    address_id TEXT,
    parameters TEXT,
    status TEXT NOT NULL,
    submitted_at TEXT NOT NULL,
    started_at TEXT,
    finished_at TEXT,
    execution_id TEXT,
    result TEXT,
    error TEXT
);

CREATE INDEX idx_remote_jobs_status ON remote_jobs(status);
CREATE INDEX idx_remote_jobs_submitted ON remote_jobs(submitted_at);
