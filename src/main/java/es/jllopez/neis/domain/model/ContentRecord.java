package es.jllopez.neis.domain.model;

import java.time.Instant;

/**
 * One stored content artifact (US-11.2, issue #42): the output of one
 * task over one webpage capture, kept in the capture's per-task
 * content folder (US-11.3). The path is the deterministic filesystem
 * tree location (US-11.4) and is unique across the content root; the
 * record id is derived deterministically from
 * address+capture+task+file, so re-running a task over the same
 * webpage UPDATES its record instead of duplicating it.
 *
 * <p>US-16.6: the record carries the id of the execution (run) that
 * last produced it, so a run's detail can list ITS content instead of
 * every capture of the address. Nullable by design — records written
 * before the feature (and artifacts written outside a run) are
 * address-scoped and stay visible.</p>
 */
public record ContentRecord(String id, String addressId, String captureId,
                            String taskCode, String kind, String path, Instant createdAt,
                            String executionId) {

    public ContentRecord {
        if (addressId == null || addressId.isBlank()) {
            throw new IllegalArgumentException("A content record needs an address id");
        }
        if (captureId == null || captureId.isBlank()) {
            throw new IllegalArgumentException("A content record needs a capture id");
        }
        if (taskCode == null || taskCode.isBlank()) {
            throw new IllegalArgumentException("A content record needs a task code");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("A content record needs a kind");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A content record needs a path");
        }
        // executionId is intentionally nullable: pre-existing records
        // are unattributed (US-16.6).
    }
}
