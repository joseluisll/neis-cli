package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.ContentRecord;

import java.util.List;
import java.util.Optional;

/**
 * Store port for the content tree (US-11, epic #11): webpage captures
 * (US-11.4) and the task outputs kept as content (US-11.2/US-11.3),
 * mirrored between the deterministic filesystem tree and the database
 * ({@code captures}/{@code content_records} tables).
 */
public interface ContentStorePort {

    /** Upsert of a capture: deterministic ids make repeats no-ops. */
    Capture saveCapture(Capture capture);

    Optional<Capture> findCapture(String addressId, String captureId);

    /** Upsert of a content record (deterministic record ids). */
    ContentRecord saveContent(ContentRecord record);

    /** Every content record, newest first. */
    List<ContentRecord> findAll();

    /** Every content record of one address, newest first. */
    List<ContentRecord> findByAddress(String addressId);
}
