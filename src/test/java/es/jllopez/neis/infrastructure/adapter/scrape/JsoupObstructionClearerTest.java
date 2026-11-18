package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.ClearedContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Obstruction clearing (US-06.7, issue #75): the jsoup connector
 * removes popups, cookie banners, modal overlays and advertisement
 * nodes from the captured HTML5 — generic rules plus the configured
 * page-specific selectors — and NEVER the main content (the guard).
 */
class JsoupObstructionClearerTest {

    private final JsoupObstructionClearer clearer = new JsoupObstructionClearer();

    @Test
    void removesADeclaredModalDialog() {
        String html = "<html><body><main><ul class='news-list'><li>Item</li></ul></main>"
                + "<div role='dialog' id='signup'><p>Subscribe!</p></div></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertFalse(cleared.html().contains("Subscribe!"));
        assertTrue(cleared.html().contains("Item"));
        assertEquals(1, cleared.removedCount());
        assertTrue(cleared.rulesApplied().contains("declared-overlays"));
    }

    @Test
    void removesACookieConsentBannerByVocabulary() {
        String html = "<html><body>"
                + "<div id='cookie-consent-banner' class='wt-cck--banner'>"
                + "<p>We use cookies</p></div>"
                + "<main><ul class='news-list'><li>Item</li></ul></main></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertFalse(cleared.html().contains("We use cookies"));
        assertTrue(cleared.html().contains("Item"));
        assertTrue(cleared.rulesApplied().contains("consent-banners"));
    }

    @Test
    void removesAdvertisementContainers() {
        String html = "<html><body><main><ul class='news-list'><li>Item</li></ul></main>"
                + "<ins class='adsbygoogle'>ad</ins>"
                + "<div id='div-gpt-ad-123'>ad</div></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertFalse(cleared.html().contains("adsbygoogle"));
        assertFalse(cleared.html().contains("div-gpt-ad"));
        assertTrue(cleared.html().contains("Item"));
        assertTrue(cleared.rulesApplied().contains("advertisements"));
    }

    @Test
    void appliesTheConfiguredPageSpecificSelectors() {
        String html = "<html><body><main><ul class='news-list'><li>Item</li></ul></main>"
                + "<div id='site-overlay'><p>promo</p></div></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of("#site-overlay"));

        assertFalse(cleared.html().contains("promo"));
        assertTrue(cleared.html().contains("Item"));
        assertTrue(cleared.rulesApplied().contains("#site-overlay"));
    }

    @Test
    void configuredSelectorMatchingNothingRemovesNothing() {
        String html = "<html><body><main><p>Content</p></main></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of(".not-there"));

        assertEquals(0, cleared.removedCount());
        assertTrue(cleared.rulesApplied().isEmpty());
        assertTrue(cleared.html().contains("Content"));
    }

    @Test
    void theGuardKeepsAContentWrapperAndRemovesOnlyItsChrome() {
        // The overlay wrapper CONTAINS the main content: the wrapper is
        // kept (removing it would remove the listing); its own banner
        // child is removed by the banner rule.
        String html = "<html><body>"
                + "<div class='modal-overlay'>"
                + "<div id='cookie-consent-banner'>cookies</div>"
                + "<main><ul class='news-list'><li>Item</li></ul></main>"
                + "</div></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertTrue(cleared.html().contains("Item"));
        assertFalse(cleared.html().contains("cookies"));
        // The wrapper itself survives the declared-overlay sweep because
        // it carries main content (the guard).
        assertTrue(cleared.html().contains("modal-overlay"));
    }

    @Test
    void aConfiguredSelectorRemovesAContentBearingWrapperItAimsAt() {
        // US-06.7 review non-blocking finding 2: the guard used to keep
        // a wrapper with >1 article even when the operator's configured
        // selector explicitly aimed at it. Configured selectors are the
        // override — the target is removed; the guard stays for the
        // generic rules only (see theGenericRulesStillRespectTheGuard).
        String html = "<html><body><div id='site-overlay'>"
                + "<article>One</article><article>Two</article>"
                + "</div></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of("#site-overlay"));

        assertFalse(cleared.html().contains("One"));
        assertFalse(cleared.html().contains("Two"));
        assertEquals(1, cleared.removedCount());
        assertTrue(cleared.rulesApplied().contains("#site-overlay"));
    }

    @Test
    void aCleanPageIsUntouched() {
        String html = "<html><body><main><article>"
                + "<h3>Title</h3><p>Body</p></article></main></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertEquals(0, cleared.removedCount());
        assertTrue(cleared.rulesApplied().isEmpty());
        assertTrue(cleared.html().contains("Title"));
    }

    @Test
    void clearingIsRecordedForTheCaptureMetadata() {
        String html = "<html><body><div role='dialog'>x</div>"
                + "<ins class='adsbygoogle'>a</ins>"
                + "<div class='cookie-banner'>c</div></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertEquals(3, cleared.removedCount());
        assertEquals(List.of("declared-overlays", "consent-banners", "advertisements"),
                cleared.rulesApplied());
    }

    @Test
    void anOpenDialogElementIsRemoved() {
        String html = "<html><body><main><p>Content</p></main>"
                + "<dialog open><p>Modal text</p></dialog></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertFalse(cleared.html().contains("Modal text"));
        assertTrue(cleared.html().contains("Content"));
    }

    @Test
    void nestedMatchesCountAsOneLogicalRemoval() {
        // An ad wrapper that itself matches a second ad fragment: the
        // descendant's remove() is a no-op on the detached subtree —
        // the record must state ONE logical removal, not two select
        // hits (review non-blocking finding).
        String html = "<html><body><main><p>Content</p></main>"
                + "<div class='ad-container'><div class='advert-box'>ad</div></div>"
                + "</body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertEquals(1, cleared.removedCount());
        assertFalse(cleared.html().contains("advert-box"));
    }

    @Test
    void theAdVocabularyDoesNotDeleteASiteResultsContainer() {
        // US-06.7 review non-blocking finding 1: the open
        // [class*=advert-] fragment matched a site's OWN results
        // container (advert-results-list) and silently deleted listing
        // content. Narrowed away — such a class is not removed by the
        // generic ad rule (a true ad nuisance there is the configured
        // page-specific selectors' job).
        String html = "<html><body><main>"
                + "<ul class='advert-results-list'><li>Listing item</li></ul>"
                + "</main></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertEquals(0, cleared.removedCount());
        assertTrue(cleared.html().contains("Listing item"),
                "the site's own listing must survive the generic ad rule");
    }

    @Test
    void aConfiguredSelectorOverridesTheMainContentGuard() {
        // US-06.7 review non-blocking finding 2: the guard kept a
        // signup/country modal WITH a select even when the operator's
        // configured selector explicitly aimed at it — configured
        // selectors are the override and bypass the guard.
        String html = "<html><body><main><ul class='news-list'><li>Item</li></ul></main>"
                + "<div id='country-modal'><form><select><option>EN</option></select></form></div>"
                + "</body></html>";

        ClearedContent cleared = clearer.clear(html, List.of("#country-modal"));

        assertFalse(cleared.html().contains("country-modal"),
                "the operator's selector removes its target even when it carries a form select");
        assertEquals(1, cleared.removedCount());
        assertTrue(cleared.rulesApplied().contains("#country-modal"));
    }

    @Test
    void theGenericRulesStillRespectTheGuard() {
        // The guard stays for the GENERIC sweeps: a dialog wrapper
        // carrying a form select (the filter-widget signal) is kept.
        String html = "<html><body>"
                + "<div role='dialog'><form><select><option>x</option></select></form></div>"
                + "<main><p>Content</p></main></body></html>";

        ClearedContent cleared = clearer.clear(html, List.of());

        assertTrue(cleared.html().contains("role='dialog'")
                        || cleared.html().contains("role=\"dialog\""),
                "the guarded generic rule keeps the content-bearing wrapper");
    }
}
