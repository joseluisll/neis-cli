package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for registered addresses (US-02.1). Part of the
 * driven adapter: the application reaches it only through the repository
 * port wired in the connector.
 */
public interface AddressJpaRepository extends JpaRepository<AddressEntity, String> {

    List<AddressEntity> findByType(es.jllopez.neis.domain.model.AddressType type);
}
