package es.jllopez.neis.infrastructure.adapter.catalog;

import es.jllopez.neis.domain.model.WebpageType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration binding of the webpage-type taxonomy extensions
 * (US-06.5, issue #71): {@code neis.webpage.types.<id>.strategy} adds
 * a new type to the taxonomy (or re-maps a built-in's strategy)
 * WITHOUT any domain change — the configuration-driven extension
 * point the requirement asks for.
 *
 * <p>YAML shape:</p>
 * <pre>
 * neis:
 *   webpage:
 *     types:
 *       spa-svelte:
 *         strategy: rendered
 *       wcm:
 *         strategy: http
 * </pre>
 */
@ConfigurationProperties(prefix = "neis.webpage.types")
public class WebpageTypeProperties {

    /** Extra taxonomy entries keyed by type id; each names its retrieval strategy. */
    private Map<String, Entry> types = new LinkedHashMap<>();

    public Map<String, Entry> getTypes() {
        return types;
    }

    public void setTypes(Map<String, Entry> types) {
        this.types = types;
    }

    /** One configured taxonomy entry: the type's retrieval strategy. */
    public static class Entry {
        private String strategy;

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }
    }
}
