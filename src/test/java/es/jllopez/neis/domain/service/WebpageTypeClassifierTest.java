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
}
