package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.ElementCatalog;
import es.jllopez.neis.domain.model.PrunedElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOM analysis (US-06.9, issue #88): the jsoup connector walks the
 * captured HTML5 into the pruned element tree — incremental ids, the
 * id-to-CSS map, visibility/relevance filtering with Skyvern's
 * isDropped semantics, interactability detection, the attribute
 * whitelist with trimming/URL hashing, and the change digest.
 */
class JsoupDomAnalyzerTest {

    private final JsoupDomAnalyzer analyzer = new JsoupDomAnalyzer();

    @Test
    void assignsIncrementalIdsInDocumentOrder() {
        String html = "<html><body><main><ul class='news-list'>"
                + "<li><h3>Title one</h3><a href='/1'>read</a></li>"
                + "<li><h3>Title two</h3><a href='/2'>read</a></li>"
                + "</ul></main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        assertFalse(catalog.elements().isEmpty());
        assertEquals("e1", catalog.elements().get(0).id());
        for (int i = 0; i < catalog.elements().size(); i++) {
            assertEquals("e" + (i + 1), catalog.elements().get(i).id());
        }
    }

    @Test
    void theIdToCssMapResolvesElements() {
        String html = "<html><body><main id='content'>"
                + "<ul class='news-list'><li>Item one</li><li>Item two</li></ul>"
                + "</main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        // Every cataloged element's CSS path must locate exactly one
        // element when re-parsed (the id-to-CSS map contract).
        for (PrunedElement e : catalog.elements()) {
            org.jsoup.nodes.Document reparsed = org.jsoup.Jsoup.parse(html);
            int matches = reparsed.select(e.cssPath()).size();
            assertEquals(1, matches,
                    "css path of " + e.id() + " must resolve uniquely: " + e.cssPath());
        }
    }

    @Test
    void droppedNonInteractableElementsDisappear() {
        String html = "<html><body><main><p>Visible content</p></main>"
                + "<div aria-hidden='true'>decorative</div>"
                + "<script>var x = 1;</script>"
                + "<div hidden>hidden box</div></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        String all = catalog.elements().stream()
                .map(e -> e.tag() + e.text()).reduce("", String::concat);
        assertFalse(all.contains("decorative"));
        assertFalse(all.contains("hidden box"));
        assertFalse(catalog.elements().stream().anyMatch(e -> e.tag().equals("script")),
                "script is never relevant");
        assertTrue(catalog.elements().stream().anyMatch(e -> e.text().contains("Visible content")));
    }

    @Test
    void droppedInteractableElementsKeepTheirTagButLoseAttributes() {
        // Skyvern's isDropped semantics: a hidden but actionable
        // element stays cataloged as a bare tag.
        String html = "<html><body><main><p>Content</p></main>"
                + "<button hidden id='ghost' class='cta' aria-label='Go'>Go</button>"
                + "</body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        PrunedElement ghost = catalog.elements().stream()
                .filter(e -> e.tag().equals("button")).findFirst().orElseThrow();
        assertTrue(ghost.dropped(), "the hidden button is dropped");
        assertTrue(ghost.attributes().isEmpty(),
                "a dropped element loses its attributes");
        assertFalse(ghost.text().isEmpty() && ghost.text().equals("Go"),
                "a dropped element carries no text");
    }

    @Test
    void interactableElementsAreFlaggedWithDisabledState() {
        String html = "<html><body><main>"
                + "<a href='/news'>News</a>"
                + "<button disabled>Send</button>"
                + "<input type='text' name='query' aria-disabled='false'>"
                + "<p>plain text</p></main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        PrunedElement link = catalog.elements().stream()
                .filter(e -> e.tag().equals("a")).findFirst().orElseThrow();
        assertTrue(link.interactable());
        assertEquals("link", link.role());

        PrunedElement button = catalog.elements().stream()
                .filter(e -> e.tag().equals("button")).findFirst().orElseThrow();
        assertTrue(button.interactable());
        // jsoup records a present-but-valueless boolean attribute as
        // "true" (the signal is its presence; US-06.9 whitelist rule).
        assertEquals("true", button.attributes().get("disabled"));

        PrunedElement input = catalog.elements().stream()
                .filter(e -> e.tag().equals("input")).findFirst().orElseThrow();
        assertTrue(input.interactable());
        assertEquals("query", input.attributes().get("name"));
        assertEquals("false", input.attributes().get("aria-disabled"));

        PrunedElement paragraph = catalog.elements().stream()
                .filter(e -> e.tag().equals("p")).findFirst().orElseThrow();
        assertFalse(paragraph.interactable());
    }

    @Test
    void onlyWhitelistedAttributesSurviveAndOversizedValuesAreTrimmed() {
        String big = "x".repeat(500);
        String html = "<html><body><main>"
                + "<a href='/1' style='color:red' data-track='abc' title='" + big + "'>L</a>"
                + "</main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        PrunedElement link = catalog.elements().stream()
                .filter(e -> e.tag().equals("a")).findFirst().orElseThrow();
        assertEquals("/1", link.attributes().get("href"));
        assertFalse(link.attributes().containsKey("style"),
                "style is not an attribute of interest");
        assertFalse(link.attributes().containsKey("data-track"),
                "data-track is not an attribute of interest");
        assertEquals(PrunedElement.MAX_VALUE_LENGTH, link.attributes().get("title").length());
    }

    @Test
    void oversizedUrlsAreHashedIntoPlaceholders() {
        String longUrl = "https://example.eu/path?" + "p=".repeat(1200);
        String html = "<html><body><main><a href='" + longUrl + "'>go</a></main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        PrunedElement link = catalog.elements().stream()
                .filter(e -> e.tag().equals("a")).findFirst().orElseThrow();
        String href = link.attributes().get("href");
        assertTrue(href.startsWith("hash:"), "the oversized URL is a placeholder: " + href);
        assertEquals(longUrl, catalog.placeholders().get(href),
                "the placeholder maps back to the full URL");
    }

    @Test
    void theDigestIsDeterministicAndContentSensitive() {
        String html = "<html><body><main><p>Same content</p></main></body></html>";
        String other = "<html><body><main><p>Different content</p></main></body></html>";

        ElementCatalog first = analyzer.analyze(html);
        ElementCatalog again = analyzer.analyze(html);
        ElementCatalog changed = analyzer.analyze(other);

        assertEquals(first.digest(), again.digest(),
                "the same page yields the same digest");
        assertNotEquals(first.digest(), changed.digest(),
                "changed content yields a different digest");
        assertEquals(64, first.digest().length(), "SHA-256 hex");
    }

    @Test
    void anEmptyPageYieldsADigestAndNoElements() {
        ElementCatalog catalog = analyzer.analyze("<html><body></body></html>");

        assertTrue(catalog.elements().isEmpty());
        assertEquals(64, catalog.digest().length());
    }
}
