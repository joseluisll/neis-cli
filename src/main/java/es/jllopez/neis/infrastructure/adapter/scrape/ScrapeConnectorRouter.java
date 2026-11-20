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
 *   <li>{@code cached} (US-06.14, issue #138) — serves the address's
 *   latest stored scrape ({@link CachedScrapeConnector}): no network,
 *   no fixture, honest CACHED provenance on the result; fail-closed
 *   when nothing is stored.</li>
 * </ul>
 *
 * <p>US-06.14's opt-in {@code neis.scrape.fallback-to-stored} (default
 * false) lets a FAILED static/live fetch of the plain-listing path
 * serve the stored content instead of failing the job — provenance
 * marked, never silent.</p>
 *
 * <p>Per-run override (WEBSCRAPE {@code connector} parameter,
 * US-12.6-style): {@code fixtures}, {@code http}, {@code rendered} or
 * {@code cached} pins the strategy for that run regardless of the page
 * type; an unknown value fails the run — a typo must never fetch with
 * the wrong strategy. The default is static on purpose: live fetching
 * is an opt-in; a null or blank value resolves to the default; an
 * unknown non-blank value fails the boot.</p>
 */
@Primary
@Component
public class ScrapeConnectorRouter implements ContentScrapePort {

    private static final Logger log = LoggerFactory.getLogger(ScrapeConnectorRouter.class);

    private final ContentScrapePort staticConnector;
    private final ObjectProvider<HttpScrapeConnector> httpConnector;
    private final ObjectProvider<PlaywrightRenderConnector> renderedConnector;
    private final CachedScrapeConnector cachedConnector;
    private final WebpageTypeCatalogPort webpageTypes;
    private volatile boolean live;
    private volatile boolean cached;
    /**
     * US-06.14: when true, a FAILED primary fetch (static or live —
     * never a cached-mode fetch itself) falls back to the latest
     * stored content instead of failing the job. Default false: the
     * failure semantics are unchanged.
     */
    private final boolean fallbackToStored;

    public ScrapeConnectorRouter(StaticScrapeConnector staticConnector,
                                 ObjectProvider<HttpScrapeConnector> httpConnector,
                                 ObjectProvider<PlaywrightRenderConnector> renderedConnector,
                                 CachedScrapeConnector cachedConnector,
                                 WebpageTypeCatalogPort webpageTypes,
                                 @Value("${neis.scrape.connector:static}") String mode,
                                 @Value("${neis.scrape.fallback-to-stored:false}")
                                 boolean fallbackToStored) {
        this.staticConnector = staticConnector;
        this.httpConnector = httpConnector;
        this.renderedConnector = renderedConnector;
        this.cachedConnector = cachedConnector;
        this.webpageTypes = webpageTypes;
        this.fallbackToStored = fallbackToStored;
        String normalized = validateMode(mode);
        this.live = normalized.equals("live");
        this.cached = normalized.equals("cached");
    }

    /** Boot + override validation, shared (US-06.14 added 'cached'). */
    private static String validateMode(String mode) {
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
            case "cached" -> log.info("SCRAPE connector: cached — serves the "
                    + "latest stored scrape (neis.scrape.connector=cached, "
                    + "US-06.14); no network, no fixture");
            default -> throw new IllegalArgumentException(
                    "Unknown neis.scrape.connector mode '" + mode
                            + "' (expected 'static', 'live' or 'cached')");
        }
        return normalized;
    }

    /**
     * Applies a persisted/live override of {@code neis.scrape.connector}
     * (US-16.5 edit path): the same validation as the boot constructor —
     * an unknown value is refused loud, never half-applied. Takes effect
     * for every subsequent scrape in this process.
     */
    public void updateMode(String mode) {
        String normalized = validateMode(mode);
        this.live = normalized.equals("live");
        this.cached = normalized.equals("cached");
        log.info("SCRAPE connector override applied: {}", normalized);
    }

    @Override
    public ScrapeResult scrape(Address address) {
        if (cached) {
            return cachedConnector.scrape(address);
        }
        if (!live) {
            return staticConnector.scrape(address);
        }
        return withStoredFallback(address,
                () -> forAddress(address).scrape(address));
    }

    @Override
    public ScrapeResult scrape(Address address, int pageIndex) {
        if (cached) {
            return cachedConnector.scrape(address, pageIndex);
        }
        if (!live) {
            return staticConnector.scrape(address, pageIndex);
        }
        return forAddress(address).scrape(address, pageIndex);
    }

    @Override
    public ScrapeResult scrapeUrl(String url) {
        if (cached) {
            throw new UnsupportedOperationException(
                    "The cached connector cannot fetch arbitrary URLs: " + url
                            + " — it serves the address's stored listing "
                            + "(US-06.14); use a live/static mode for "
                            + "pagination next-page fetches");
        }
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
            case "cached" -> cachedConnector.scrape(address);
            default -> throw new IllegalArgumentException(
                    "Unknown connector override '" + connectorOverride
                            + "' (expected 'fixtures', 'http', 'rendered' or 'cached')");
        };
    }

    /**
     * US-06.14's opt-in fallback (default false): when the primary
     * fetch of the plain-listing path FAILS, the latest stored content
     * serves instead of failing the job — the provenance note marks
     * the reuse, so a served-but-stale page is never silent. Only the
     * plain scrape path falls back: a failed PAGINATION page or filter
     * application has no honest stored equivalent (the stored scrape
     * is the unfiltered main listing). A cached-mode fetch never
     * falls back (it IS the cached path; its own failures are loud).
     */
    private ScrapeResult withStoredFallback(Address address,
                                            java.util.function.Supplier<ScrapeResult> primary) {
        if (!fallbackToStored) {
            return primary.get();
        }
        try {
            return primary.get();
        } catch (RuntimeException e) {
            log.warn("SCRAPE {}: primary fetch failed ({}: {}) — falling "
                            + "back to the latest stored content "
                            + "(neis.scrape.fallback-to-stored=true, US-06.14)",
                    address.id(), e.getClass().getSimpleName(), e.getMessage());
            return cachedConnector.scrape(address);
        }
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
     *
     * <p>The per-run {@code connector} override is HONORED here (US-06.6
     * review finding: the filtered path used to ignore it): {@code
     * fixtures} serves the offline capture, {@code http} forces the
     * query-parameter path, {@code rendered} forces the control path —
     * regardless of the address's classified type AND of the
     * {@code neis.scrape.connector} mode; an unknown value fails the
     * run, the same rule as the unfiltered override.</p>
     */
    @Override
    public ScrapeResult scrapeWithFilters(Address address,
                                          java.util.Map<String, String> filters,
                                          String connectorOverride) {
        if (filters == null || filters.isEmpty()) {
            return scrape(address, connectorOverride);
        }
        String override = connectorOverride == null
                ? "" : connectorOverride.trim().toLowerCase(java.util.Locale.ROOT);
        // The override is resolved BEFORE the mode short-circuit
        // (blocking review finding on the queue-fix commit: the old
        // !live check ran first, so static mode silently served the
        // fixtures for an http/rendered override — and an unknown
        // override never failed loud). The same values mean the same
        // thing in every mode: 'fixtures' is the offline capture;
        // 'http'/'rendered' force the live connector, exactly like the
        // unfiltered scrape(address, override); 'cached' serves the
        // stored listing — WITH filters it fails loud (stored content
        // predates the filter set; US-06.14).
        switch (override) {
            case "fixtures" -> {
                return staticConnector.scrapeWithFilters(address, filters, null);
            }
            case "http" -> {
                return httpConnector.getObject()
                        .scrapeUrl(withQueryParams(address.url(), filters));
            }
            case "rendered" -> {
                return renderedConnector.getObject()
                        .scrapeWithFilters(address, filters, null);
            }
            case "cached" -> {
                return cachedConnector.scrapeWithFilters(address, filters, null);
            }
            default -> {
                if (!override.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Unknown connector override '" + connectorOverride
                                    + "' (expected 'fixtures', 'http', 'rendered' "
                                    + "or 'cached')");
                }
            }
        }
        if (cached) {
            return cachedConnector.scrapeWithFilters(address, filters, null);
        }
        if (!live) {
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
     * appended after '?' or '&' as the existing URL requires. The
     * fragment is respected (US-06.6 review finding: appending after
     * {@code #...} would swallow the parameters into the fragment —
     * they are inserted before it). A key the URL already carries is
     * refused loud: a duplicated query parameter would make the
     * server-side filter ambiguous.
     */
    static String withQueryParams(String url, java.util.Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return url;
        }
        String base = url;
        String fragment = "";
        int hash = url.indexOf('#');
        if (hash >= 0) {
            base = url.substring(0, hash);
            fragment = url.substring(hash);
        }
        if (base.contains("?")) {
            String existingQuery = base.substring(base.indexOf('?') + 1);
            for (String pair : existingQuery.split("&")) {
                String key = pair.contains("=")
                        ? pair.substring(0, pair.indexOf('=')) : pair;
                for (String name : filters.keySet()) {
                    // The key is matched raw, in its URL-encoded form,
                    // and URL-DECODED (a '%20'-encoded key must be
                    // caught too — review finding; decode failures
                    // leave the raw key, which the raw comparison
                    // still sees).
                    if (key.equals(name) || key.equals(urlEncoded(name))
                            || urlDecoded(key).equals(name)) {
                        throw new IllegalArgumentException("The URL already carries '"
                                + key + "' — the filter '" + name
                                + "' would be applied twice; remove the filter or "
                                + "fix the address URL (US-06.6)");
                    }
                }
            }
        }
        StringBuilder query = new StringBuilder();
        for (var e : filters.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(urlEncoded(e.getKey()))
                    .append('=')
                    .append(urlEncoded(e.getValue()));
        }
        return base + (base.contains("?") ? "&" : "?") + query + fragment;
    }

    private static String urlEncoded(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The URL-decoded query key, or the raw key when it does not decode. */
    private static String urlDecoded(String key) {
        try {
            return java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return key;
        }
    }

    @Override
    public List<FilterRequestOption> supportedFilterRequests(Address address) {
        if (cached) {
            return cachedConnector.supportedFilterRequests(address);
        }
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
