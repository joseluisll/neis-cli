package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.port.ContentStorePort;
import es.jllopez.neis.infrastructure.persistence.CaptureEntity;
import es.jllopez.neis.infrastructure.persistence.CaptureJpaRepository;
import es.jllopez.neis.infrastructure.persistence.ContentRecordEntity;
import es.jllopez.neis.infrastructure.persistence.ContentRecordJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Driven adapter implementing {@link ContentStorePort} over the
 * Spring Data JPA repositories (US-11, epic #11): webpage captures in
 * the SQLite {@code captures} table of schema V2 and content records
 * in the {@code content_records} table of schema V1. Both sides are
 * upserts on deterministic ids, so re-running a task over the same
 * webpage updates its rows instead of duplicating them.
 */
@Component
public class ContentStoreConnector implements ContentStorePort {

    private final CaptureJpaRepository captures;
    private final ContentRecordJpaRepository records;

    public ContentStoreConnector(CaptureJpaRepository captures,
                                 ContentRecordJpaRepository records) {
        this.captures = captures;
        this.records = records;
    }

    @Override
    @Transactional
    public Capture saveCapture(Capture capture) {
        CaptureEntity entity = captures
                .findByAddressIdAndCaptureId(capture.addressId(), capture.captureId())
                .orElseGet(CaptureEntity::new);
        entity.setAddressId(capture.addressId());
        entity.setCaptureId(capture.captureId());
        entity.setUrl(capture.url());
        entity.setCreatedAt(capture.createdAt() == null ? null : capture.createdAt().toString());
        captures.save(entity);
        return capture;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Capture> findCapture(String addressId, String captureId) {
        return captures.findByAddressIdAndCaptureId(addressId, captureId)
                .map(e -> new Capture(e.getAddressId(), e.getCaptureId(), e.getUrl(),
                        e.createdAtInstant()));
    }

    @Override
    @Transactional
    public ContentRecord saveContent(ContentRecord record) {
        ContentRecordEntity entity = records.findById(record.id())
                .orElseGet(ContentRecordEntity::new);
        entity.setId(record.id());
        entity.setAddressId(record.addressId());
        entity.setCaptureId(record.captureId());
        entity.setTaskCode(record.taskCode());
        entity.setKind(record.kind());
        entity.setPath(record.path());
        entity.setCreatedAt(record.createdAt() == null ? null : record.createdAt().toString());
        records.save(entity);
        return record;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentRecord> findAll() {
        return records.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(e -> new ContentRecord(e.getId(), e.getAddressId(), e.getCaptureId(),
                        e.getTaskCode(), e.getKind(), e.getPath(), e.createdAtInstant()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentRecord> findByAddress(String addressId) {
        return records.findByAddressIdOrderByCreatedAtDesc(addressId).stream()
                .map(e -> new ContentRecord(e.getId(), e.getAddressId(), e.getCaptureId(),
                        e.getTaskCode(), e.getKind(), e.getPath(), e.createdAtInstant()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContentRecord> findById(String id) {
        return records.findById(id)
                .map(e -> new ContentRecord(e.getId(), e.getAddressId(), e.getCaptureId(),
                        e.getTaskCode(), e.getKind(), e.getPath(), e.createdAtInstant()));
    }
}
