package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for the {@code captures} table (US-11.4). */
public interface CaptureJpaRepository extends JpaRepository<CaptureEntity, CaptureEntity.Key> {

    Optional<CaptureEntity> findByAddressIdAndCaptureId(String addressId, String captureId);
}
