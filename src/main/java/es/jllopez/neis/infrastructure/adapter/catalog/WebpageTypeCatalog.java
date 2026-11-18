package es.jllopez.neis.infrastructure.adapter.catalog;

import es.jllopez.neis.domain.model.WebpageType;
import es.jllopez.neis.domain.port.WebpageTypeCatalogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter implementing {@link WebpageTypeCatalogPort}: the
 * built-in taxonomy of US-06.5 (issue #71) plus the configuration
 * extensions ({@code neis.webpage.types.<id>.strategy}, see
 * {@link WebpageTypeProperties}). Adding a webpage type is a
 * configuration edit — never a domain change.
 *
 * <p>Built-in strategy mapping: every {@code spa-*} type and
 * {@code hybrid} render in a browser ({@link WebpageType#STRATEGY_RENDERED});
 * {@code static} and {@code other} fetch over plain HTTP
 * ({@link WebpageType#STRATEGY_HTTP}); {@code feed} sources are
 * fetched and parsed as RSS/Atom documents
 * ({@link WebpageType#STRATEGY_FEED}, US-06.8) — an API-only source
 * needs no rendering, and a mislabeled one can be re-mapped through
 * configuration or overridden per run.</p>
 */
@Component
@EnableConfigurationProperties(WebpageTypeProperties.class)
public class WebpageTypeCatalog implements WebpageTypeCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(WebpageTypeCatalog.class);

    /** The built-in taxonomy: id -> retrieval strategy. Entries are
     * inserted in DECLARATION order into a LinkedHashMap (a Map.of/
     * Map.copyOf iteration order is salted per JVM, JDK 9+ — the
     * catalog's all() order flows into user-facing type lists and must
     * be deterministic across CLI runs; US-06.10 review non-blocking
     * finding, the repo's determinism rule from US-06.9 review B2). */
    private static final Map<String, String> BUILT_INS =
            new LinkedHashMap<>();
    static {
        BUILT_INS.put("static", WebpageType.STRATEGY_HTTP);
        BUILT_INS.put("spa", WebpageType.STRATEGY_RENDERED);
        BUILT_INS.put("spa-angular", WebpageType.STRATEGY_RENDERED);
        BUILT_INS.put("spa-react", WebpageType.STRATEGY_RENDERED);
        BUILT_INS.put("spa-vue", WebpageType.STRATEGY_RENDERED);
        BUILT_INS.put("hybrid", WebpageType.STRATEGY_RENDERED);
        BUILT_INS.put("feed", WebpageType.STRATEGY_FEED);
        BUILT_INS.put("other", WebpageType.STRATEGY_HTTP);
    }

    private final Map<String, WebpageType> types;

    public WebpageTypeCatalog(WebpageTypeProperties properties) {
        Map<String, WebpageType> map = new LinkedHashMap<>();
        BUILT_INS.forEach((id, strategy) -> map.put(id, new WebpageType(id, strategy)));
        properties.getTypes().forEach((rawId, entry) -> {
            String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                throw new IllegalArgumentException(
                        "neis.webpage.types: a configured webpage type needs an id");
            }
            String strategy = entry == null ? null : entry.getStrategy();
            // US-06.10 review finding: a null/blank strategy used to
            // silently bind to HTTP — a config typo (an id written
            // without its strategy) must fail the boot loud, exactly
            // like an unknown strategy value, never downgrade quietly.
            if (strategy == null || strategy.isBlank()) {
                throw new IllegalArgumentException("neis.webpage.types." + rawId
                        + ": a configured webpage type needs a strategy ('"
                        + WebpageType.STRATEGY_HTTP + "', '"
                        + WebpageType.STRATEGY_RENDERED + "' or '"
                        + WebpageType.STRATEGY_FEED + "')");
            }
            map.put(id, new WebpageType(id, strategy.trim()));
        });
        // Insertion order preserved (LinkedHashMap, unmodifiable copy):
        // the taxonomy's all() order is user-facing and must be
        // deterministic across JVMs (US-06.10 review non-blocking
        // finding; the Map.copyOf hash order is JVM-salted).
        this.types = java.util.Collections.unmodifiableMap(map);
        log.info("Webpage type taxonomy: {} types ({} configured extensions)",
                types.size(), properties.getTypes().size());
    }

    @Override
    public Optional<WebpageType> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(types.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    @Override
    public List<WebpageType> all() {
        return List.copyOf(types.values());
    }
}
