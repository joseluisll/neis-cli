package es.jllopez.neis.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Spring Data repository for the {@code chain_tasks} table (US-08.1). */
public interface ChainTaskJpaRepository extends JpaRepository<ChainTaskEntity, Long> {

    List<ChainTaskEntity> findByChainIdOrderByPositionAsc(String chainId);

    /**
     * Bulk delete (immediate SQL, not flush-ordered entity removal):
     * replacing a chain's task rows must remove the old ones BEFORE the
     * new inserts hit the (chain_id, position) UNIQUE constraint.
     */
    @Modifying
    @Query("delete from ChainTaskEntity t where t.chainId = :chainId")
    void deleteByChainId(@Param("chainId") String chainId);
}
