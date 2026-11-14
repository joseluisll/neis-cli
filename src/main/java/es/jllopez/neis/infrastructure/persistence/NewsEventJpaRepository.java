package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository over the {@code news_events} table (US-14.1). */
public interface NewsEventJpaRepository extends JpaRepository<NewsEventEntity, String> {

    List<NewsEventEntity> findByAddressIdOrderByUpdatedAtDesc(String addressId);
}
