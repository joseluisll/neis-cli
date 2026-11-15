package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.FilterRequestOption;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.WebpageType;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.WebpageTypeCatalogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Routing connector for the SCRAPE port: picks the ACTIVE scraping
 * connector from configuration ({@code neis.scrape.connector}), and —
 * since US-06.5 (issue #71) — the retrieval strategy of each fetch
 * from the webpage-type taxonomy:
 *
 * <ul>
 *   <li>{@code static} (default) — the bundled HTML5 fixtures of the
 *   issue #62 seed addresses ({@link StaticScrapeConnector}); no
 *   network access at run time, the core-kit offline runs keep
 *   working unchanged. Every fetch, whatever the page type, serves
 *   the fixtures.</li>
 *   <li>{@code live} — PER-TYPE routing: the address's classified
 *   webpage type (US-06.5) selects the retrieval strategy —
 *   {@code http} types go to the real HTTP fetcher
 *   ({@link HttpScrapeConnector}, US-06.1) with jsoup normalization,
 *   {@code rendered} types (spa-*, hybrid) go to the browser-rendering
 *   connector ({@link PlaywrightRenderConnector}); an unclassified
 *   address falls back to HTTP (logged); an unknown type id fails
 *   loud — a stale taxonomy id must never silently downgrade to HTTP.</li>
 * </ul>
 *
 * <p>Per-run override (WEBSCRAPE {@code connector} parameter,
 * US-12.6-style): {@code fixtures}, {@code http} or {@code rendered}
 * pins the strategy for that run regardless of the page type; an
 * unknown value fails the run — a typo must never fetch with the
 * wrong strategy. The default is static on purpose: live fetching is
 * an opt-in; a null or blank value resolves to the default; an
 * unknown non-blank value fails the boot.</p>
 */
@Primary
@Component
public class ScrapeConnectorRouter implements ContentScrapePort {

    private static final Logger log = LoggerFactory.getLogger(ScrapeConnectorRouter.class);

    private final ContentScrapePort staticConnector;
    private final ObjectProvider<HttpScrapeConnector> httpConnector;
    private final ObjectProvider<PlaywrightRenderConnector> renderedConnector;
    private final WebpageTypeCatalogPort webpageTypes;
    private final boolean live;

    public ScrapeConnectorRouter(StaticScrapeConnector staticConnector,
                                 ObjectProvider<HttpScrapeConnector> httpConnector,
                                 ObjectProvider<PlaywrightRenderConnector> renderedConnector,
                                 WebpageTypeCatalogPort webpageTypes,
                                 @Value("${neis.scrape.connector:static}") String mode) {
        this.staticConnector = staticConnector;
        this.httpConnector = httpConnector;
        this.renderedConnector = renderedConnector;
        this.webpageTypes = webpageTypes;
        String normalized = mode == null ? "" : mode.trim();
        if (normalized.isEmpty()) {
            normalized = "static"; // unset property resolves to the default
        }
        switch (normalized) {
            case "static" -> log.info("SCRAPE connector: static fixtures "
                    + "(neis.scrape.connector=static)");
            case "live" -> log.info("SCRAPE connector: live per-type retrieval "
                    + "(neis.scrape.connector=live) — http or browser rendering "
                    + "by the address's webpage type");
            default -> throw new IllegalArgumentException(
                    "Unknown neis.scrape.connector mode '" + mode
                            + "' (expected 'static' or 'live')");
        }
        this.live = normalized.equals("live");
    }

    @Override
    public ScrapeResult scrape(Address address) {
        if (!live) {
            return staticConnector.scrape(address);
        }
        return forAddress(address).scrape(address);
    }

    @Override
    public ScrapeResult scrape(Address address, int pageIndex) {
        if (!live) {
            return staticConnector.scrape(address, pageIndex);
        }
        return forAddress(address).scrape(address, pageIndex);
    }

    @Override
    public ScrapeResult scrapeUrl(String url) {
        // An arbitrary URL (US-12.4 pagination next-page) fetches over
        // HTTP: the rendered strategy is selected per ADDRESS type, and
        // a next-page URL of an SPA listing is the documented gap
        // (rendered pagination needs the page's own controls — a
        // connector-level capability, not a URL fetch).
        return httpConnector.getObject().scrapeUrl(url);
    }

    @Override
    public ScrapeResult scrape(Address address, String connectorOverride) {
        if (connectorOverride == null || connectorOverride.isBlank()) {
            return scrape(address);
        }
        String strategy = connectorOverride.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (strategy) {
            case "fixtures" -> staticConnector.scrape(address);
            case "http" -> httpConnector.getObject().scrape(address);
            case "rendered" -> renderedConnector.getObject().scrape(address);
            default -> throw new IllegalArgumentException(
                    "Unknown connector override '" + connectorOverride
                            + "' (expected 'fixtures', 'http' or 'rendered')");
        };
    }

    @Override
    public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
        if (!live) {
            return staticConnector.scrapeFiltered(address, filterRequest);
        }
        return forAddress(address).scrapeFiltered(address, filterRequest);
    }

    /**
     * US-06.6 (issue #73): the WEBSCRAPE filter set applied before
     * extraction. Strategy-specific application — query parameters on
     * the URL for the {@code http} strategy, operated filter controls
     * for the {@code rendered} strategy; the fixtures connector serves
     * its captured filtered responses offline; a feed-classified
     * address is refused loud (feeds are parsed directly, US-06.8).
     * An empty filter set is the plain scrape.
     */
    @Override
    public ScrapeResult scrapeWithFilters(Address address,
                                          java.util.Map<String, String> filters,
                                          String connectorOverride) {
        if (filters == null || filters.isEmpty()) {
            return scrape(address, connectorOverride);
        }
        if (!live || "fixtures".equalsIgnoreCase(
                connectorOverride == null ? "" : connectorOverride.trim())) {
            return staticConnector.scrapeWithFilters(address, filters, null);
        }
        String strategy = strategyFor(address);
        if (WebpageType.STRATEGY_FEED.equals(strategy)) {
            throw new UnsupportedOperationException(
                    "Filter controls do not apply to feed sources (US-06.6/06.8): "
                            + address.id() + " is parsed directly — remove the filters");
        }
        if (WebpageType.STRATEGY_RENDERED.equals(strategy)) {
            return renderedConnector.getObject().scrapeWithFilters(address, filters, null);
        }
        return httpConnector.getObject().scrapeUrl(withQueryParams(address.url(), filters));
    }

    /** The address's strategy in the live taxonomy (unclassified → http). */
    private String strategyFor(Address address) {
        return webpageTypes.byId(address.webpageType())
                .map(WebpageType::strategy)
                .orElse(WebpageType.STRATEGY_HTTP);
    }

    /**
     * The static URL with the filter set as query parameters
     * (US-06.6): name=value pairs, URL-encoded, in filter order,
     * appended after '?' or '&' as the existing URL requires.
     */
    static String withQueryParams(String url, java.util.Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        for (var e : filters.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(java.net.URLEncoder.encode(e.getKey(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    @Override
    public List<FilterRequestOption> supportedFilterRequests(Address address) {
        if (!live) {
            return staticConnector.supportedFilterRequests(address);
        }
        return forAddress(address).supportedFilterRequests(address);
    }

    /** The strategy connector of the address's classified webpage type. */
    private ContentScrapePort forAddress(Address address) {
        String typeId = address.webpageType();
        if (typeId == null || typeId.isBlank()) {
            log.info("SCRAPE {}: webpage type not classified yet — HTTP fetch "
                    + "(verify the address to classify it, or override per run)",
                    address.id());
            return httpConnector.getObject();
        }
        return webpageTypes.byId(typeId)
                .map(type -> switch (type.strategy()) {
                    case WebpageType.STRATEGY_RENDERED -> renderedConnector.getObject();
                    default -> httpConnector.getObject();
                })
                .orElseThrow(() -> new IllegalStateException(
                        "Address " + address.id() + " carries webpage type '"
                                + typeId + "', which the taxonomy does not know — "
                                + "re-verify the address or fix its type"));
    }
}
