package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.Capture;
import es.jllopez.neis.domain.model.ContentRecord;
import es.jllopez.neis.domain.port.ContentStorePort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of {@link ContentStorePort}: concurrent maps (task work
 * runs on executor threads while the caller inspects). Ordering
 * follows the port contract: content records newest first, by created
 * date (the SQLite connector orders its query the same way).
 */
public class InMemoryContentStorePort implements ContentStorePort {

    private final Map<String, Map<String, Capture>> captures = new ConcurrentHashMap<>();
    private final Map<String, ContentRecord> records = new ConcurrentHashMap<>();

    @Override
    public Capture saveCapture(Capture capture) {
        captures.computeIfAbsent(capture.addressId(), key -> new ConcurrentHashMap<>())
                .put(capture.captureId(), capture);
        return capture;
    }

    @Override
    public Optional<Capture> findCapture(String addressId, String captureId) {
        Map<String, Capture> ofAddress = captures.get(addressId);
        return ofAddress == null
                ? Optional.empty() : Optional.ofNullable(ofAddress.get(captureId));
    }

    @Override
    public ContentRecord saveContent(ContentRecord record) {
        records.put(record.id(), record);
        return record;
    }

    @Override
    public List<ContentRecord> findAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(ContentRecord::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public List<ContentRecord> findByAddress(String addressId) {
        return records.values().stream()
                .filter(record -> record.addressId().equals(addressId))
                .sorted(Comparator.comparing(ContentRecord::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public Optional<ContentRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }
}
