package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.port.NewsEventStorePort;
import es.jllopez.neis.infrastructure.persistence.NewsEventEntity;
import es.jllopez.neis.infrastructure.persistence.NewsEventJpaRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * Connector (driven adapter) implementing the news/events repository
 * over the SQLite {@code news_events} table (US-14.1, issue #55).
 *
 * <p>Idempotent upsert (US-15.4): the record id is DETERMINISTIC —
 * derived from the address and the item's detail URL (or its title when
 * the item has no link) — so re-extraction updates the same row instead
 * of duplicating it. The unique index in schema V3 backs the same
 * guarantee at the storage level.</p>
 */
@Component
public class NewsEventStoreConnector implements NewsEventStorePort {

    private final NewsEventJpaRepository jpa;

    public NewsEventStoreConnector(NewsEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public int saveAll(String addressId, String kind, List<NewsEventItem> items,
                       String executionId, String contentId) {
        String now = Instant.now().toString();
        int stored = 0;
        for (NewsEventItem item : items) {
            String id = deterministicId(addressId, item);
            NewsEventEntity entity = jpa.findById(id).orElseGet(NewsEventEntity::new);
            boolean isNew = entity.getId() == null;
            entity.setId(id);
            entity.setAddressId(addressId);
            entity.setKind(kind == null ? item.kind() : kind);
            entity.setTitle(item.title());
            entity.setDescription(item.description());
            entity.setDate(item.date());
            // A blank-but-non-null url (raw href fallback in the
            // extractor) would break the unique index's COALESCE key:
            // normalize to null so the title-based key applies
            // (US-14.1 review finding, 2026-11-14).
            entity.setUrl(item.url() == null || item.url().isBlank()
                    ? null : item.url());
            // US-14.2 provenance: the execution and the scrape.html
            // content record this extraction came from.
            entity.setExecutionId(executionId);
            entity.setContentId(contentId);
            if (isNew) {
                entity.setCreatedAt(now);
            }
            entity.setUpdatedAt(now);
            jpa.save(entity);
            stored++;
        }
        return stored;
    }

    @Override
    public List<NewsEventItem> byAddress(String addressId) {
        return jpa.findByAddressIdOrderByUpdatedAtDesc(addressId).stream()
                .map(e -> new NewsEventItem(e.getAddressId(), e.getKind(),
                        e.getTitle(), e.getDescription(), e.getUrl(), e.getDate()))
                .toList();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    /**
     * The deterministic storage key: address + the item's detail URL,
     * or its title when the item has no link (the same key the schema's
     * unique index enforces).
     */
    private static String deterministicId(String addressId, NewsEventItem item) {
        String naturalKey = addressId + "|"
                + (item.url() != null && !item.url().isBlank()
                        ? item.url().trim()
                        : "title:" + (item.title() == null ? "" : item.title().trim()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(naturalKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ne-");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
