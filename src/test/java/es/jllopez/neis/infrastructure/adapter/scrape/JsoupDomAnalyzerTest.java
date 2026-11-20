package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.application.PipelineService;
import es.jllopez.neis.domain.model.ElementCatalog;
import es.jllopez.neis.domain.model.NewsEventItem;
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
    void urlValuesBetweenTheCapsAreKeptIntact() {
        // Issue #144: a URL of 201-2048 chars must NOT fall under the
        // 200-char generic value cap — a substring of a URL is a
        // CORRUPTED url (mid-query), and abs-resolution then stores
        // junk. URL-typed attributes (href/action/src) are exempt at
        // any length: intact up to MAX_URL_LENGTH, hashed beyond.
        String abs = "https://example.eu/seccion/noticia-con-titulo-largo"
                + "/pagina".repeat(30); // 262 chars: above 200, below 2048
        String rel = "/seccion/otra/" + "b".repeat(250);
        String html = "<html><body><main>"
                + "<a href='" + abs + "'>abs</a>"
                + "<a href='" + rel + "'>rel</a>"
                + "<a title='" + "x".repeat(300) + "'>txt</a>"
                + "</main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);
        var links = catalog.elements().stream()
                .filter(e -> e.tag().equals("a")).toList();
        assertEquals(3, links.size());
        assertEquals(abs, links.get(0).attributes().get("href"),
                "an absolute URL of 201-2048 chars is kept INTACT");
        assertEquals(rel, links.get(1).attributes().get("href"),
                "a RELATIVE href (URL by attribute name) is kept INTACT");
        assertEquals(PrunedElement.MAX_VALUE_LENGTH,
                links.get(2).attributes().get("title").length(),
                "a non-URL value is still capped at 200");

        // the pruned DOM the extraction reads keeps the URLs too
        java.util.Map<String, String> placeholders = new java.util.LinkedHashMap<>();
        org.jsoup.nodes.Document pruned = analyzer.prune(html, placeholders);
        assertEquals(abs, pruned.select("a").get(0).attr("href"));
        assertEquals(rel, pruned.select("a").get(1).attr("href"));
    }

    @Test
    void longHrefsSurviveSimplifyAndExtractionEndToEnd() {
        // Issue #144 END TO END: the URL an item carries after
        // SIMPLIFY (the pruned tree the FULL tier renders) + selector
        // extraction is the FULL href, not a 200-char substring.
        String rel = "/seccion/noticia/" + "l".repeat(250);
        String html = "<html><body><main><ul class='news-list'>"
                + "<li><h3>Una noticia con titular</h3>"
                + "<a href='" + rel + "'>leer mas</a></li>"
                + "</ul></main></body></html>";

        JsoupSimplifyConnector simplify = new JsoupSimplifyConnector(
                new JsoupDomAnalyzer(), new PrunedTreeHtmlRenderer());
        var simplified = simplify.simplify(html);
        List<NewsEventItem> items = new JsoupSelectorConnector().extract(
                simplified.html(), "ul.news-list li", "NEWS", "ADDR-010", 10,
                "https://example.eu/portada");

        assertEquals(1, items.size());
        assertEquals("https://example.eu" + rel, items.get(0).url(),
                "the stored item URL is the complete href, abs-resolved");
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

    @Test
    void cssPathsResolveUniquelyWithoutIdAnchors() {
        // Review B1 counterexample: body-level containers whose tag
        // repeats in the document (a nested div is also a first-of-tag
        // child of ITS parent). Every emitted path must resolve to
        // exactly one element on a re-parse — no id anchors to mask it.
        String html = "<html><body>"
                + "<div><div>x</div></div>"
                + "<div>y</div>"
                + "<section><p>a</p></section>"
                + "<p>b</p>"
                + "</body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        assertFalse(catalog.elements().isEmpty());
        org.jsoup.nodes.Document reparsed = org.jsoup.Jsoup.parse(html);
        for (PrunedElement e : catalog.elements()) {
            assertEquals(1, reparsed.select(e.cssPath()).size(),
                    "css path of " + e.id() + " must resolve uniquely: " + e.cssPath());
        }
    }

    @Test
    void theCatalogRendersIdenticallyAcrossAttributeOrders() {
        // Review B2: the whitelist order is FIXED (a List, not a
        // salted-per-JVM Set) — the same page must render the same
        // attrs sequence and the same digest in ANY JVM. Pinned by
        // rendering the JSON twice and comparing byte-for-byte, and by
        // asserting the attrs order matches the whitelist declaration
        // order (not a hash order).
        String html = "<html><body><main>"
                + "<a href='/1' title='T' class='c' role='link' rel='next'>L</a>"
                + "</main></body></html>";

        ElementCatalog first = analyzer.analyze(html);
        ElementCatalog second = analyzer.analyze(html);

        assertEquals(PipelineService.catalogJson(first),
                PipelineService.catalogJson(second),
                "the catalog JSON is deterministic");
        var attrs = first.elements().stream()
                .filter(e -> e.tag().equals("a")).findFirst().orElseThrow()
                .attributes();
        var expectedOrder = PrunedElement.WHITELISTED_ATTRIBUTES.stream()
                .filter(attrs::containsKey).toList();
        assertEquals(expectedOrder, List.copyOf(attrs.keySet()),
                "attrs keep the whitelist's fixed order");
    }

    @Test
    void inputRolesFollowTheInputType() {
        // Review non-blocking finding: the flat input→textbox mapping
        // mislabeled every non-text control. The role follows the
        // input's type; an explicit role attribute still wins.
        String html = "<html><body><main>"
                + "<input type='checkbox' name='flags'>"
                + "<input type='radio' name='group'>"
                + "<input type='submit' value='Go'>"
                + "<input type='range' min='0' max='10'>"
                + "<input type='text' name='query'>"
                + "</main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        var byRole = catalog.elements().stream()
                .filter(e -> e.tag().equals("input"))
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.attributes().getOrDefault("type", ""),
                        e -> e.role(),
                        (a, b) -> a));
        assertEquals("checkbox", byRole.get("checkbox"));
        assertEquals("radio", byRole.get("radio"));
        assertEquals("button", byRole.get("submit"));
        assertEquals("slider", byRole.get("range"));
        assertEquals("textbox", byRole.get("text"));
    }

    @Test
    void anExplicitRoleStillWinsOverTheImplicitMapping() {
        String html = "<html><body><main>"
                + "<input type='submit' role='search' value='Find'>"
                + "</main></body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        PrunedElement input = catalog.elements().stream()
                .filter(e -> e.tag().equals("input")).findFirst().orElseThrow();
        assertEquals("search", input.role());
    }

    @Test
    void hiddenDetectionToleratesStyleWhitespaceAndCase() {
        // Review non-blocking finding: 'display :  none' (spaces around
        // the colon, mixed case) is as hidden as 'display:none'.
        String html = "<html><body><main><p>Visible</p></main>"
                + "<div style='display :  none'>spacing variant</div>"
                + "<div style='DISPLAY:NONE'>case variant</div>"
                + "<div style='visibility : hidden'>visibility variant</div>"
                + "</body></html>";

        ElementCatalog catalog = analyzer.analyze(html);

        String all = catalog.elements().stream()
                .map(e -> e.text()).reduce("", String::concat);
        assertFalse(all.contains("spacing variant"));
        assertFalse(all.contains("case variant"));
        assertFalse(all.contains("visibility variant"));
        assertTrue(all.contains("Visible"));
    }
}
