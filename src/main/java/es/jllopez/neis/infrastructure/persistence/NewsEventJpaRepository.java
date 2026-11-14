package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Spring Data repository over the {@code news_events} table (US-14.1). */
public interface NewsEventJpaRepository extends JpaRepository<NewsEventEntity, String> {

    List<NewsEventEntity> findByAddressIdOrderByUpdatedAtDesc(String addressId);

    List<NewsEventEntity> findAllByOrderByUpdatedAtDesc();

    /**
     * The US-14.3 filtered search: address and kind optional (null = no
     * filter), newest first, UNBOUNDED — the date range is evaluated by
     * the caller (the connector) through
     * {@link es.jllopez.neis.domain.model.NewsEventQuery#dateInRange},
     * because stored item dates come in several formats (ISO and the
     * extractor's human form) that SQL cannot parse.
     */
    @Query("""
            select e from NewsEventEntity e
            where (:addressId is null or e.addressId = :addressId)
              and (:kind is null or e.kind = :kind)
            order by e.updatedAt desc
            """)
    List<NewsEventEntity> search(@Param("addressId") String addressId,
                                 @Param("kind") String kind);

    /** The rows forming one logical record (US-14.5 canonical id). */
    List<NewsEventEntity> findByCanonicalId(String canonicalId);
}
