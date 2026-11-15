package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.domain.model.NewsEventSighting;
import es.jllopez.neis.domain.port.NewsEventStorePort;
import es.jllopez.neis.infrastructure.persistence.NewsEventEntity;
import es.jllopez.neis.infrastructure.persistence.NewsEventJpaRepository;
import es.jllopez.neis.infrastructure.persistence.NewsEventSightingEntity;
import es.jllopez.neis.infrastructure.persistence.NewsEventSightingJpaRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    private final NewsEventSightingJpaRepository sightingJpa;

    public NewsEventStoreConnector(NewsEventJpaRepository jpa,
                                   NewsEventSightingJpaRepository sightingJpa) {
        this.jpa = jpa;
        this.sightingJpa = sightingJpa;
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
            // A null title would abort the whole batch on the schema's
            // NOT NULL (US-14.4 review): it is stored as empty and the
            // completeness evaluation above already flags it — the row
            // lands INCOMPLETE(missing: title), never as a crash.
            entity.setTitle(item.title() == null ? "" : item.title());
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
            // US-14.4: optional metadata is captured when present;
            // completeness is evaluated by the store on every save, so
            // an upsert that fixes the data clears the incomplete flag
            // (and an upsert that loses data sets it).
            entity.setPicture(item.picture());
            entity.setLocation(item.location());
            List<String> missing = item.missingMandatory();
            entity.setIncomplete(!missing.isEmpty());
            entity.setMissingFields(missing.isEmpty()
                    ? null : String.join(", ", missing));
            // US-14.5 dedup: the deterministic canonical id — the same
            // story seen again (later capture, paginated page, other
            // address) lands on the same id without any lookup.
            entity.setCanonicalId(item.computedCanonicalId());
            if (isNew) {
                entity.setCreatedAt(now);
            }
            entity.setUpdatedAt(now);
            jpa.save(entity);
            stored++;
            // US-14.5: every capture that produced the item adds a
            // sighting — the appearance history, never a new record.
            sightingJpa.save(sighting(id, addressId, executionId, contentId, now));
        }
        return stored;
    }

    @Override
    public List<NewsEventItem> byAddress(String addressId) {
        return jpa.findByAddressIdOrderByUpdatedAtDesc(addressId).stream()
                .map(NewsEventStoreConnector::toItem)
                .toList();
    }

    @Override
    public List<NewsEventItem> all() {
        return jpa.findAllByOrderByUpdatedAtDesc().stream()
                .map(NewsEventStoreConnector::toItem)
                .toList();
    }

    @Override
    public List<NewsEventItem> search(NewsEventQuery query) {
        // Address and kind in SQL; the date range in Java through the
        // query's lenient parser (stored dates are ISO or human-formatted
        // — SQL cannot compare those), then the limit. The unbounded
        // fetch is fine at the repository's local scale (US-14.3).
        return jpa.search(
                        blankToNull(query.addressId()),
                        blankToNull(query.kind())).stream()
                .map(NewsEventStoreConnector::toItem)
                .filter(item -> query.dateInRange(item.date()))
                .limit(query.limit())
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public long count() {
        return jpa.count();
    }

    private static NewsEventItem toItem(NewsEventEntity e) {
        return new NewsEventItem(e.getAddressId(), e.getKind(),
                e.getTitle(), e.getDescription(), e.getUrl(), e.getDate(),
                e.getPicture(), e.getLocation(),
                e.getExecutionId(), e.getContentId(),
                e.isIncomplete(), e.getMissingFields(),
                e.getCanonicalId());
    }

    @Override
    public List<NewsEventSighting> sightings(String canonicalId) {
        // The logical record (canonical id) may span several rows (one
        // per address that saw it): every row's sightings belong to it.
        return jpa.findByCanonicalId(canonicalId).stream()
                .flatMap(e -> sightingJpa
                        .findByItemIdOrderBySeenAtDesc(e.getId()).stream())
                .sorted(java.util.Comparator.comparing(
                        NewsEventSightingEntity::getSeenAt).reversed())
                .map(s -> new NewsEventSighting(s.getItemId(), s.getAddressId(),
                        s.getContentId(), s.getExecutionId(),
                        Instant.parse(s.getSeenAt())))
                .toList();
    }

    /** One sighting row: a deterministic id keeps an identical
     * (item, execution, content) sighting idempotent, while a new
     * capture (new execution/content pair) adds a new sighting. */
    private static NewsEventSightingEntity sighting(String itemId,
                                                    String addressId,
                                                    String executionId,
                                                    String contentId,
                                                    String now) {
        NewsEventSightingEntity s = new NewsEventSightingEntity();
        String naturalKey = itemId + "|" + executionId + "|" + contentId;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(naturalKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ne-s-");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            s.setId(sb.toString());
        } catch (java.security.NoSuchAlgorithmException e) {
            s.setId("ne-s-" + UUID.randomUUID());
        }
        s.setItemId(itemId);
        s.setAddressId(addressId);
        s.setExecutionId(executionId);
        s.setContentId(contentId);
        s.setSeenAt(now);
        return s;
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
