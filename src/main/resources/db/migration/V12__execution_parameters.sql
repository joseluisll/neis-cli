-- V12 (US-16.3, issue #97 review finding): the run's input parameters
-- are part of the execution record's traceability — without them a
-- re-run cannot be faithful (it would resubmit with defaults) and the
-- run detail cannot show what the run was given. Stored as a JSON
-- object of string->string; NULL for runs without parameters (chain
-- runs carry their per-step parameters in the chain definition).
ALTER TABLE executions ADD COLUMN parameters TEXT;
