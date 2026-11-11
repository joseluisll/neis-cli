package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.StoredSelectors;

import java.util.Optional;

/**
 * Port for the selector store: validated working selectors persisted
 * per address so later runs skip AI identification entirely (owner
 * decision 2026-11-11, MVP issue #90).
 */
public interface SelectorStorePort {

    /** Stored working selector for the address, if any. */
    Optional<StoredSelectors> load(String addressId);

    /** Persists (or replaces) the working selector of the address. */
    void save(StoredSelectors selectors);
}
