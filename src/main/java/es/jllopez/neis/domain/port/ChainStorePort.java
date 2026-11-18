package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.Chain;

import java.util.List;
import java.util.Optional;

/**
 * Store port for chain definitions (US-08.1, issue #33): the named,
 * ordered task lists the executor runs (US-08.2). Backed by the
 * {@code chains}/{@code chain_tasks} tables of schema V1.
 */
public interface ChainStorePort {

    Chain save(Chain chain);

    Optional<Chain> findById(String id);

    Optional<Chain> findByName(String name);

    /** Every chain definition, ordered by name. */
    List<Chain> findAll();

    /**
     * Removes the chain definition with the given id (US-10.1, issue
     * #39: chain management delete). Unknown ids are a no-op — the
     * service layer refuses them first (loud), the port is the raw
     * store. Executions that already ran keep their records.
     */
    void deleteById(String id);
}
