package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository over the {@code news_event_sightings} table
 * (US-14.5): the appearance history of the stored news/event items. */
public interface NewsEventSightingJpaRepository
        extends JpaRepository<NewsEventSightingEntity, String> {

    /** A sightings timeline is read newest first (ISO TEXT sorts
     * chronologically). */
    List<NewsEventSightingEntity> findByItemIdOrderBySeenAtDesc(String itemId);
}
