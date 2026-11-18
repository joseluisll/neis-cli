package es.jllopez.neis.infrastructure.adapter.catalog;

import es.jllopez.neis.domain.model.WebpageType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration-driven webpage-type taxonomy (US-06.5, issue
 * #71): the built-in types resolve to their documented strategies and
 * a configured entry ADDS a type (or re-maps a built-in's strategy)
 * with no domain change; a bad strategy fails the boot.
 */
class WebpageTypeCatalogTest {

    @Test
    void builtInTypesResolveToTheirDocumentedStrategies() {
        WebpageTypeCatalog catalog = new WebpageTypeCatalog(new WebpageTypeProperties());

        assertEquals(WebpageType.STRATEGY_HTTP, catalog.byId("static").orElseThrow().strategy());
        assertEquals(WebpageType.STRATEGY_HTTP, catalog.byId("other").orElseThrow().strategy());
        for (String id : new String[] {"spa", "spa-angular", "spa-react",
                "spa-vue", "hybrid"}) {
            assertEquals(WebpageType.STRATEGY_RENDERED,
                    catalog.byId(id).orElseThrow().strategy(), id);
        }
        assertEquals(8, catalog.all().size());
    }

    @Test
    void configuredEntriesExtendTheTaxonomy() {
        WebpageTypeProperties properties = new WebpageTypeProperties();
        WebpageTypeProperties.Entry svelte = new WebpageTypeProperties.Entry();
        svelte.setStrategy("rendered");
        WebpageTypeProperties.Entry wcm = new WebpageTypeProperties.Entry();
        wcm.setStrategy("http");
        properties.getTypes().put("spa-svelte", svelte);
        properties.getTypes().put("wcm", wcm);

        WebpageTypeCatalog catalog = new WebpageTypeCatalog(properties);

        assertEquals("rendered", catalog.byId("spa-svelte").orElseThrow().strategy());
        assertEquals("http", catalog.byId("wcm").orElseThrow().strategy());
        assertEquals(10, catalog.all().size());
    }

    @Test
    void configuredEntriesRemapABuiltInsStrategy() {
        WebpageTypeProperties properties = new WebpageTypeProperties();
        WebpageTypeProperties.Entry entry = new WebpageTypeProperties.Entry();
        entry.setStrategy("http");
        properties.getTypes().put("hybrid", entry);

        WebpageTypeCatalog catalog = new WebpageTypeCatalog(properties);

        assertEquals("http", catalog.byId("hybrid").orElseThrow().strategy());
    }

    @Test
    void configuredEntryWithoutStrategyFailsTheBoot() {
        // US-06.10 review finding: a null/blank strategy used to bind
        // silently to HTTP; the boot now refuses it loud, naming the
        // offending key — same rule as an unknown strategy value.
        WebpageTypeProperties properties = new WebpageTypeProperties();
        properties.getTypes().put("mystery", new WebpageTypeProperties.Entry());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WebpageTypeCatalog(properties));
        assertTrue(e.getMessage().contains("mystery"), e.getMessage());
        assertTrue(e.getMessage().contains("strategy"), e.getMessage());

        // The blank form fails identically (a whitespace-only YAML value).
        WebpageTypeProperties blank = new WebpageTypeProperties();
        WebpageTypeProperties.Entry empty = new WebpageTypeProperties.Entry();
        empty.setStrategy("   ");
        blank.getTypes().put("mystery", empty);
        assertThrows(IllegalArgumentException.class, () -> new WebpageTypeCatalog(blank));
    }

    @Test
    void unknownIdsAreUnknown() {
        WebpageTypeCatalog catalog = new WebpageTypeCatalog(new WebpageTypeProperties());

        assertFalse(catalog.byId("spa-svelte").isPresent());
        assertFalse(catalog.isKnown(null));
        assertFalse(catalog.isKnown("  "));
        assertTrue(catalog.isKnown("SPA-Angular"), "ids match case-insensitively");
    }

    @Test
    void invalidStrategyFailsTheBoot() {
        WebpageTypeProperties properties = new WebpageTypeProperties();
        WebpageTypeProperties.Entry entry = new WebpageTypeProperties.Entry();
        entry.setStrategy("selenium");
        properties.getTypes().put("spa-svelte", entry);

        assertThrows(IllegalArgumentException.class,
                () -> new WebpageTypeCatalog(properties));
    }

    @Test
    void allRendersInDeclarationOrderDeterministically() {
        // US-06.10 review non-blocking finding: the taxonomy lived in
        // Map.of/Map.copyOf copies whose iteration order is salted per
        // JVM (JDK 9+) — the same configuration listed the types in a
        // different order in every CLI run. Pinned: all() equals the
        // documented built-in declaration order (then the configured
        // extensions in configuration order).
        WebpageTypeProperties properties = new WebpageTypeProperties();
        WebpageTypeProperties.Entry svelte = new WebpageTypeProperties.Entry();
        svelte.setStrategy("rendered");
        properties.getTypes().put("spa-svelte", svelte);

        WebpageTypeCatalog catalog = new WebpageTypeCatalog(properties);

        var ids = catalog.all().stream().map(WebpageType::id).toList();
        assertEquals(java.util.List.of("static", "spa", "spa-angular",
                "spa-react", "spa-vue", "hybrid", "feed", "other",
                "spa-svelte"), ids);
    }
}
