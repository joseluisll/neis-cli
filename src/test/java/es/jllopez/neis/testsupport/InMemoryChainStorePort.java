package es.jllopez.neis.testsupport;

import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.port.ChainStorePort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of {@link ChainStorePort}: a concurrent map (chain runs
 * read definitions from worker threads). Ordering follows the port
 * contract: by name (the SQLite connector orders its query the same
 * way).
 */
public class InMemoryChainStorePort implements ChainStorePort {

    private final Map<String, Chain> chains = new ConcurrentHashMap<>();

    @Override
    public Chain save(Chain chain) {
        chains.put(chain.id(), chain);
        return chain;
    }

    @Override
    public Optional<Chain> findById(String id) {
        return Optional.ofNullable(chains.get(id));
    }

    @Override
    public Optional<Chain> findByName(String name) {
        return chains.values().stream()
                .filter(c -> c.name().equals(name))
                .findFirst();
    }

    @Override
    public List<Chain> findAll() {
        return chains.values().stream()
                .sorted(Comparator.comparing(Chain::name))
                .toList();
    }
}
