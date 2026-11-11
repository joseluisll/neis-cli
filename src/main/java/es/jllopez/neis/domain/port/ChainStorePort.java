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
}
