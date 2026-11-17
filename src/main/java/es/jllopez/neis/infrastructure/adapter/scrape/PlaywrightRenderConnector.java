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
        return render(url, java.util.Map.of());
    }

    /**
     * US-06.6 (issue #73), rendered form: the filter set is applied by
     * operating the page's own filter controls BEFORE the extraction —
     * a {@code <select>} whose name/id/label matches the filter name
     * gets the option selected (by value, then by label), a text
     * input/search/date gets the value filled, a checkbox/radio gets
     * checked; a submit/apply button is clicked when the page has one
     * and the page re-settles (NETWORKIDLE). A control that cannot be
     * found, or an option the control does not offer, fails LOUD — a
     * silently unfiltered listing would poison the repository.
     */
    @Override
    public ScrapeResult scrapeWithFilters(Address address,
                                          java.util.Map<String, String> filters,
                                          String connectorOverride) {
        return render(address.url(), filters);
    }

    private ScrapeResult render(String url, java.util.Map<String, String> filters) {
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
            // Obstruction dismissal (US-06.7, issue #75): the browser
            // interaction layer closes what can be closed — popup
            // windows, cookie/consent accept buttons, dialog close
            // controls — BEFORE the DOM is serialized. Best-effort by
            // design (a page with none simply finds none); the DOM
            // sweep in the clearing connector (jsoup) removes what no
            // button dismisses. The serialize step below runs on the
            // SAME page, so a dismissed overlay is gone from the
            // stored HTML5.
            dismissObstructions(page, url);
            boolean filtersApplied = false;
            com.microsoft.playwright.Locator firstControl = null;
            for (var filter : filters.entrySet()) {
                var control = applyFilterControl(page, filter.getKey(),
                        filter.getValue(), url);
                if (firstControl == null) {
                    firstControl = control;
                }
                filtersApplied = true;
            }
            if (filtersApplied) {
                boolean submitted = submitFilterZone(page, firstControl, url);
                if (!submitted) {
                    // US-06.10 review finding: the heuristic was
                    // silent — a site that needs an explicit submit
                    // click and has no recognizable control would
                    // serve an UNFILTERED listing with no trace. The
                    // heuristic stays best-effort (auto-apply pages
                    // are legitimate), but the miss is now flagged.
                    log.warn("RENDER fetch {}: filters applied to their controls but "
                            + "no submit/apply control was found in the form or the "
                            + "page — if this page does not auto-apply its filters, "
                            + "the fetched listing is UNFILTERED", url);
                }
                page.waitForLoadState(
                        com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            }
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

    /**
     * One user filter value applied to the matching page control
     * (US-06.6): the control is located by name/id/aria-label/data
     * attributes (case-insensitive on the attribute VALUE). A select
     * gets the option chosen by value first, then by visible label; a
     * text/search/date-like input gets the value filled; a checkbox is
     * checked only when its declared value matches the filter value; a
     * radio picks the group member whose value (or visible label)
     * matches. No matching control, a value the control does not
     * offer, or a mismatched checkbox value fails loud — a silently
     * unfiltered listing would poison the repository.
     *
     * @return the matched control (the submit heuristic scopes itself
     *         to the control's own form first)
     */
    private com.microsoft.playwright.Locator applyFilterControl(
            com.microsoft.playwright.Page page,
            String name, String value, String url) {
        String attr = name.replace("\"", "");
        String controlSelector = "[name=\"" + attr + "\" i], [id=\"" + attr + "\" i], "
                + "[aria-label=\"" + attr + "\" i], [data-filter=\"" + attr + "\" i], "
                + "[placeholder=\"" + attr + "\" i]";
        var control = page.locator(controlSelector).first();
        if (control.count() == 0) {
            throw new IllegalStateException("Filter control for '" + name
                    + "' not found on " + url + " (US-06.6) — the listing cannot be "
                    + "filtered, the run is aborted");
        }
        String tag = control.evaluate("el => el.tagName.toLowerCase()").toString();
        switch (tag) {
            case "select" -> selectOption(control, name, value, url);
            case "input" -> {
                String type = control.getAttribute("type");
                if (type == null || type.isBlank()
                        || type.equalsIgnoreCase("text")
                        || type.equalsIgnoreCase("search")
                        || type.equalsIgnoreCase("email")
                        || type.equalsIgnoreCase("date")) {
                    control.fill(value);
                } else if (type.equalsIgnoreCase("checkbox")) {
                    checkCheckbox(control, name, value, url);
                } else if (type.equalsIgnoreCase("radio")) {
                    checkRadio(page, control, name, value, url);
                } else {
                    throw new IllegalStateException("Filter control for '" + name
                            + "' has unsupported input type '" + type + "' on " + url
                            + " (US-06.6)");
                }
            }
            case "textarea" -> control.fill(value);
            default -> throw new IllegalStateException("Filter control for '" + name
                    + "' is <" + tag + "> — unsupported for filtering on " + url
                    + " (US-06.6)");
        }
        return control;
    }

    /** Selects by value first, then by visible label; loud on neither. */
    private void selectOption(com.microsoft.playwright.Locator select,
                              String name, String value, String url) {
        try {
            select.selectOption(value);
            return;
        } catch (RuntimeException ignored) {
            // fall through to the label match
        }
        try {
            select.selectOption(new com.microsoft.playwright.options.SelectOption()
                    .setLabel(value));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The select control for '" + name
                    + "' offers no option '" + value + "' on " + url
                    + " (US-06.6) — the run is aborted");
        }
    }

    /**
     * A checkbox carries a value the server reads when checked
     * (default 'on' when the attribute is absent, the HTML rule):
     * checking it with a MISMATCHED filter value would silently filter
     * by the wrong thing (US-06.6 addendum review finding — the old
     * code ignored the value) — refused loud.
     */
    private void checkCheckbox(com.microsoft.playwright.Locator control,
                               String name, String value, String url) {
        String declared = control.getAttribute("value");
        String effective = declared == null || declared.isBlank()
                ? "on" : declared.trim();
        if (!effective.equalsIgnoreCase(value.trim())) {
            throw new IllegalStateException("The checkbox filter '" + name
                    + "' declares value '" + effective + "', not '" + value.trim()
                    + "' on " + url + " (US-06.6) — the run is aborted");
        }
        control.check();
    }

    /**
     * A radio group takes ONE member: the one whose value (then whose
     * visible label) matches the filter value (US-06.6 addendum review
     * finding — the old code checked the first group member and
     * ignored the value). No member matches: refused loud.
     */
    private void checkRadio(com.microsoft.playwright.Page page,
                            com.microsoft.playwright.Locator control,
                            String name, String value, String url) {
        String group = control.getAttribute("name");
        if (group == null || group.isBlank()) {
            String declared = control.getAttribute("value");
            if (declared == null || !declared.trim().equalsIgnoreCase(value.trim())) {
                throw new IllegalStateException("The radio filter '" + name
                        + "' declares value '"
                        + (declared == null ? "<none>" : declared.trim())
                        + "', not '" + value.trim() + "' on " + url
                        + " (US-06.6) — the run is aborted");
            }
            control.check();
            return;
        }
        var radios = page.locator("input[type=radio][name=\""
                + group.replace("\"", "") + "\"]");
        for (var radio : radios.all()) {
            String declared = radio.getAttribute("value");
            if (declared != null && declared.trim().equalsIgnoreCase(value.trim())) {
                radio.check();
                return;
            }
        }
        for (var radio : radios.all()) {
            String label = radio.evaluate("el => Array.from(el.labels || [])"
                    + ".map(l => l.textContent.trim()).join(' ')").toString();
            if (!label.isBlank() && label.equalsIgnoreCase(value.trim())) {
                radio.check();
                return;
            }
        }
        throw new IllegalStateException("The radio group '" + group
                + "' (filter '" + name + "') offers no option '" + value.trim()
                + "' on " + url + " (US-06.6) — the run is aborted");
    }

    /**
     * Submits the filter zone when the page has an explicit
     * submit/apply control; pages whose filters auto-apply need no
     * click (best-effort, never fails the run). The FIRST scope tried
     * is the applied control's own form (US-06.6 review finding: the
     * page-wide first-submit heuristic could click an unrelated
     * form's button); with no matching button there, or no form, the
     * page-wide scan remains the fallback.
     *
     * @return whether a submit control was found and clicked — false
     *         is flagged by the caller (US-06.10: the miss used to be
     *         silent, leaving a possibly unfiltered listing unmarked)
     */
    private boolean submitFilterZone(com.microsoft.playwright.Page page,
                                     com.microsoft.playwright.Locator control,
                                     String url) {
        var formScope = control == null ? null
                : control.locator("xpath=ancestor::form[1]");
        if (formScope != null && formScope.count() > 0) {
            for (String selector : SUBMIT_SELECTORS) {
                var button = formScope.locator(selector).first();
                if (button.count() > 0) {
                    button.click();
                    return true;
                }
            }
        }
        for (String selector : SUBMIT_SELECTORS) {
            var button = page.locator(selector).first();
            if (button.count() > 0) {
                button.click();
                return true;
            }
        }
        return false;
    }

    /** The submit/apply selectors, tried in order within each scope. */
    private static final String[] SUBMIT_SELECTORS = {
            "button[type=submit]", "input[type=submit]",
            "button:has-text(\"Apply\")", "button:has-text(\"Filter\")",
            "button:has-text(\"Search\")"};

    /**
     * Dismisses what the page itself offers to dismiss (US-06.7, issue
     * #75): consent/cookie accept buttons, dialog close controls and
     * any additional popup window the page opened. Best-effort — a
     * control that is absent, hidden or refuses the click is skipped
     * (the jsoup clearing connector handles the DOM remains); only
     * unexpected Playwright errors are contained, never propagated:
     * a failed dismissal must not fail an otherwise servable fetch.
     */
    private void dismissObstructions(com.microsoft.playwright.Page page, String url) {
        // Pages that spawned popups: their close is the dismiss.
        try {
            for (com.microsoft.playwright.Page popup : page.context().pages()) {
                if (!popup.equals(page) && !popup.isClosed()) {
                    popup.close();
                    log.info("RENDER {}: popup window dismissed (US-06.7)", url);
                }
            }
        } catch (RuntimeException e) {
            log.info("RENDER {}: popup dismissal skipped: {}", url,
                    e.getClass().getSimpleName());
        }
        // Consent/accept and dialog-close controls, in order: the
        // first clickable one is used, one per selector, never twice.
        for (String selector : DISMISS_SELECTORS) {
            try {
                var control = page.locator(selector).first();
                if (control.count() > 0 && control.isVisible()) {
                    control.click(new com.microsoft.playwright.Locator.ClickOptions()
                            .setTimeout(2000));
                    log.info("RENDER {}: obstruction dismissed via '{}' (US-06.7)",
                            url, selector);
                }
            } catch (RuntimeException e) {
                // Absent, hidden or non-clickable: the DOM sweep handles
                // what this click could not.
                log.info("RENDER {}: dismissal '{}' skipped: {}", url, selector,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * The dismissal selectors (US-06.7): the generic consent/accept
     * and dialog-close vocabulary of the web — no site-specific
     * knowledge. One click per matching selector at most.
     */
    private static final String[] DISMISS_SELECTORS = {
            "button:has-text(\"Accept all\")",
            "button:has-text(\"Accept\")",
            "button:has-text(\"I agree\")",
            "button:has-text(\"Agree\")",
            "button[aria-label*=\"cookie\" i]",
            "[role=dialog] button:has-text(\"Close\")",
            "[role=dialog] [aria-label*=\"close\" i]",
            "dialog[open] button:has-text(\"Close\")"};

    /** Deterministic shutdown of the provisioned browser and driver
     * (US-06.5 review note): without it, closing the JVM relied on
     * Playwright's thread characteristics. Idempotent — a connector
     * that never rendered has nothing to close. Also the remedy for a
     * launch failure orphan: if chromium().launch() throws after the
     * driver was provisioned, the driver instance is closed instead of
     * being left unreferenced for the JVM to reap.
     */
    @jakarta.annotation.PreDestroy
    public synchronized void shutdown() {
        if (browser != null) {
            try {
                browser.close();
            } catch (RuntimeException ignored) {
                // best effort: the process teardown continues
            }
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // best effort: the process teardown continues
            }
            playwright = null;
        }
    }

    /** The lazily provisioned browser: driver + binaries on first use. */
    private synchronized com.microsoft.playwright.Browser browser() {
        if (browser == null) {
            log.info("RENDER strategy first use: provisioning the Playwright "
                    + "driver and Chromium (one-time download on this host)");
            com.microsoft.playwright.Playwright created =
                    com.microsoft.playwright.Playwright.create();
            try {
                browser = created.chromium().launch();
            } catch (RuntimeException e) {
                // A launch failure must not orphan the provisioned
                // driver: close it so the next call provisions cleanly.
                try {
                    created.close();
                } catch (RuntimeException ignored) {
                    // best effort on the failure path
                }
                throw e;
            }
            playwright = created;
        }
        return browser;
    }
}
