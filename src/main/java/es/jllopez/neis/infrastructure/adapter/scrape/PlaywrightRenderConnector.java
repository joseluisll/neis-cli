package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.port.ContentScrapePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * The rendered retrieval strategy (US-06.5, issue #71): fetches a
 * page by executing it in a real browser — Playwright for Java (the
 * owner-confirmed stack, US-01.4) — so client-rendered SPA content is
 * fully present before extraction. Selected by the webpage-type
 * taxonomy for the {@code spa-*} and {@code hybrid} types, and per
 * run through the WEBSCRAPE {@code connector} parameter.
 *
 * <p>LAZY by design: the Playwright driver and the browser binaries
 * are provisioned on FIRST USE of this connector (a network download
 * on the host the first time a rendered strategy runs) — never at
 * application boot and never in offline runs. Offline and fixture
 * runs never touch this class.</p>
 *
 * <p>Operational discipline: ONE log line per fetch (URL, rendered
 * size, elapsed) — never page contents. The response-body cap is
 * enforced after the browser hands over the DOM: the rendered page
 * already lived in the browser process, so the cap bounds what NEIS
 * stores (same {@code sizeBytes} semantics as the HTTP connector),
 * not the browser's own memory.</p>
 */
@Lazy
@Component
public class PlaywrightRenderConnector implements ContentScrapePort {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightRenderConnector.class);

    private final int maxBytes;
    private final int timeoutSeconds;

    /** Provisioned on first use; null until then. Guarded by {@code this}. */
    private com.microsoft.playwright.Playwright playwright;
    private com.microsoft.playwright.Browser browser;

    public PlaywrightRenderConnector(
            @Value("${neis.scrape.render.max-bytes:2000000}") int maxBytes,
            @Value("${neis.scrape.render.timeout-seconds:30}") int timeoutSeconds) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "neis.scrape.render.max-bytes must be positive, got: " + maxBytes);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "neis.scrape.render.timeout-seconds must be positive, got: "
                            + timeoutSeconds);
        }
        this.maxBytes = maxBytes;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ScrapeResult scrape(Address address) {
        return scrapeUrl(address.url());
    }

    @Override
    public ScrapeResult scrapeUrl(String url) {
        long start = System.nanoTime();
        com.microsoft.playwright.BrowserContext context = null;
        try {
            context = browser().newContext(
                    new com.microsoft.playwright.Browser.NewContextOptions()
                            .setTimezoneId("UTC"));
            com.microsoft.playwright.Page page = context.newPage();
            page.setDefaultTimeout(timeoutSeconds * 1000L);
            // NETWORKIDLE: wait until the client-side application has
            // settled — the point of rendering. A page that never goes
            // idle fails at the deadline (fail loud, never half-empty).
            page.navigate(url,
                    new com.microsoft.playwright.Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE)
                            .setTimeout(timeoutSeconds * 1000L));
            String html = page.content();
            int size = html.getBytes(StandardCharsets.UTF_8).length;
            if (size > maxBytes) {
                throw new IllegalStateException("Rendered page exceeds the "
                        + maxBytes + "-byte cap (" + size + " bytes): " + url);
            }
            log.info("RENDER fetch {} -> {} bytes ({} ms, browser-rendered)",
                    url, size, (System.nanoTime() - start) / 1_000_000);
            return new ScrapeResult(html, "text/html", size);
        } catch (RuntimeException e) {
            log.info("RENDER fetch {} -> FAILED ({} , {} ms)", url,
                    e.getClass().getSimpleName(), (System.nanoTime() - start) / 1_000_000);
            throw e;
        } finally {
            // Close the CONTEXT, not the page: if newPage() itself
            // throws, the page reference never exists and a page-only
            // cleanup would leak one context per failed navigation
            // (fa42575 review finding). Closing the context always
            // releases both.
            if (context != null) {
                context.close();
            }
        }
    }

    /** The lazily provisioned browser: driver + binaries on first use. */
    private synchronized com.microsoft.playwright.Browser browser() {
        if (browser == null) {
            log.info("RENDER strategy first use: provisioning the Playwright "
                    + "driver and Chromium (one-time download on this host)");
            playwright = com.microsoft.playwright.Playwright.create();
            browser = playwright.chromium().launch();
        }
        return browser;
    }
}
