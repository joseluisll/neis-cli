package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.FilterRequestOption;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Routing connector for the SCRAPE port: picks the active scraping
 * connector from configuration ({@code neis.scrape.connector}):
 *
 * <ul>
 *   <li>{@code static} (default) — the bundled HTML5 fixtures of the
 *   issue #62 seed addresses ({@link StaticScrapeConnector}); no
 *   network access at run time, the core-kit offline runs keep
 *   working unchanged.</li>
 *   <li>{@code live} — the real HTTP fetcher ({@link HttpScrapeConnector},
 *   US-06.1, issue #27) with jsoup normalization.</li>
 * </ul>
 *
 * <p>The default is static on purpose: live fetching is an opt-in
 * until the content-type/charset handling (US-06.2) and the paginated
 * and filtered variants reach parity with the fixtures. A null or
 * blank value resolves to the default; an unknown non-blank value
 * fails the boot (a silent fallback to fixtures would masquerade a
 * configuration typo as an offline run).</p>
 */
@Primary
@Component
public class ScrapeConnectorRouter implements ContentScrapePort {

    private static final Logger log = LoggerFactory.getLogger(ScrapeConnectorRouter.class);

    private final ContentScrapePort delegate;

    public ScrapeConnectorRouter(StaticScrapeConnector staticConnector,
                                 ObjectProvider<HttpScrapeConnector> liveConnector,
                                 @Value("${neis.scrape.connector:static}") String mode) {
        String normalized = mode == null ? "" : mode.trim();
        if (normalized.isEmpty()) {
            normalized = "static"; // unset property resolves to the default
        }
        switch (normalized) {
            case "static" -> {
                this.delegate = staticConnector;
                log.info("SCRAPE connector: static fixtures (neis.scrape.connector=static)");
            }
            case "live" -> {
                this.delegate = liveConnector.getObject();
                log.info("SCRAPE connector: live HTTP fetch (neis.scrape.connector=live)");
            }
            default -> throw new IllegalArgumentException(
                    "Unknown neis.scrape.connector mode '" + mode
                            + "' (expected 'static' or 'live')");
        }
    }

    @Override
    public ScrapeResult scrape(Address address) {
        return delegate.scrape(address);
    }

    @Override
    public ScrapeResult scrape(Address address, int pageIndex) {
        return delegate.scrape(address, pageIndex);
    }

    @Override
    public ScrapeResult scrapeUrl(String url) {
        return delegate.scrapeUrl(url);
    }

    @Override
    public ScrapeResult scrapeFiltered(Address address, String filterRequest) {
        return delegate.scrapeFiltered(address, filterRequest);
    }

    @Override
    public List<FilterRequestOption> supportedFilterRequests(Address address) {
        return delegate.supportedFilterRequests(address);
    }
}
