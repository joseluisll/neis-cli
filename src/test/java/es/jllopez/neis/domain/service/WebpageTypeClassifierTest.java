package es.jllopez.neis.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The deterministic webpage-type classification (US-06.5, issue #71):
 * marker heuristics over the content type and a body sample —
 * reproducible, explainable, never AI.
 */
class WebpageTypeClassifierTest {

    private final WebpageTypeClassifier classifier = new WebpageTypeClassifier();

    @Test
    void htmlWithoutMarkersIsStatic() {
        assertEquals("static", classifier.classify("text/html",
                "<html><body><h1>News</h1><ul><li>item</li></ul></body></html>"));
    }

    @Test
    void frameworkMarkersClassifyByFamily() {
        assertEquals("spa-angular", classifier.classify("text/html",
                "<app-root ng-version=\"17.0.0\"></app-root>"));
        assertEquals("spa-react", classifier.classify("text/html",
                "<script id=\"__NEXT_DATA__\" type=\"application/json\">{}</script>"));
        assertEquals("spa-vue", classifier.classify("text/html",
                "<div id=\"app\" data-v-app></div>"));
    }

    @Test
    void frameworkMarkerPlusNoscriptFallbackIsHybrid() {
        assertEquals("hybrid", classifier.classify("text/html",
                "<div ng-version=\"17.0.0\"><noscript>Enable JS</noscript></div>"));
    }

    @Test
    void feedDocumentsClassifyAsFeed() {
        // US-06.8: a feed document is its own source type — detected
        // by content type or by the root element.
        assertEquals("feed", classifier.classify("application/rss+xml",
                "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel/></rss>"));
        assertEquals("feed", classifier.classify("application/atom+xml",
                "<?xml version=\"1.0\"?><feed xmlns=\"...\"><entry/></feed>"));
        // Served without a proper content type (or as text/html): the
        // root element decides.
        assertEquals("feed", classifier.classify("text/html",
                "<rss version=\"2.0\"><channel/></rss>"));
        assertEquals("feed", classifier.classify(null,
                "<feed xmlns=\"...\"><entry/></feed>"));
    }

    @Test
    void elementsExtendingTheFeedNameAreNotFeeds() {
        // Review finding (2026-11-15): '<feedback>' must not match
        // '<feed'. An HTML page about feedback is NOT a feed — Rome
        // would fail loud downstream, but the classification must be
        // right in the first place.
        assertEquals("static", classifier.classify("text/html",
                "<html><body><feedback-form>Please tell us</feedback-form></body></html>"));
        assertEquals("other", classifier.classify("text/xml",
                "<?xml version=\"1.0\"?><feedback><message>hi</message></feedback>"));
    }

    @Test
    void truncatedSamplesNeverCrashClassification() {
        // Review finding (2026-11-15): a body sample of exactly '<feed'
        // has no delimiter after the element name — unclassifiable as a
        // feed, never an exception.
        assertEquals("static", classifier.classify("text/html", "<feed"));
        assertEquals("static", classifier.classify("text/html", "<rss"));
        assertNull(classifier.classify("text/html", ""));
        assertNull(classifier.classify("text/html", null));
    }

    @Test
    void nonHtmlContentIsOther() {
        assertEquals("other", classifier.classify("application/json",
                "{\"items\": []}"));
        assertEquals("other", classifier.classify("application/pdf", "%PDF-1.7"));
    }

    @Test
    void missingContentTypeTreatsTheBodyAsHtml() {
        assertEquals("static", classifier.classify(null,
                "<html><body>server-rendered</body></html>"));
        assertEquals("spa-react", classifier.classify("",
                "<div data-reactroot></div>"));
    }

    @Test
    void unclassifiableBodiesYieldNull() {
        assertNull(classifier.classify("text/html", null));
        assertNull(classifier.classify("text/html", "   "));
        assertNull(classifier.classify("text/html", ""));
    }

    @Test
    void markersAreMatchedCaseInsensitively() {
        assertEquals("spa-react", classifier.classify("text/html",
                "<SCRIPT ID=\"__NEXT_DATA__\"></SCRIPT>"));
    }

    @Test
    void serverRenderedPageWithGenericMountIdIsNotSpa() {
        // US-06.5 P3 (issue #99): server-rendered pages routinely use
        // id="root"/id="app" — a mount id (even with a bundled script)
        // is NO SPA evidence, so a framework-less page stays static
        // and never routes to the heavy rendered connector.
        assertEquals("static", classifier.classify("text/html",
                "<html><body><div id=\"root\"><h1>EU4Health news</h1>"
                        + "<ul><li>item</li></ul></div>"
                        + "<script src=\"/assets/analytics.js\"></script></body></html>"));
        assertEquals("static", classifier.classify("text/html",
                "<html><body><main id=\"app\"><p>static content</p></main></body></html>"));
        assertEquals("static", classifier.classify("text/html",
                "<html><body><div id=\"root\"></div>"
                        + "<script src=\"/assets/main.abc123.js\" type=\"module\"></script>"
                        + "</body></html>"));
        // A real framework marker still wins regardless of the mount id.
        assertEquals("spa-vue", classifier.classify("text/html",
                "<div id=\"app\" data-v-app></div>"));
        // A mount id present alongside a framework marker + noscript
        // is still hybrid: the hybrid rule only needs the marker.
        assertEquals("hybrid", classifier.classify("text/html",
                "<html><body><div id=\"root\" ng-version=\"17.0.0\">"
                        + "<noscript>Enable JS</noscript></div></body></html>"));
        // Uppercase markup carries no mount-id evidence either — the
        // body is lowercased before matching, and the ids are gone.
        assertEquals("static", classifier.classify("text/html",
                "<HTML><BODY><DIV ID=\"ROOT\">content</DIV></BODY></HTML>"));
    }
}
