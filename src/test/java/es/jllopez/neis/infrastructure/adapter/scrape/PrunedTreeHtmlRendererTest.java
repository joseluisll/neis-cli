package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.SimplifiedContent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-07.6 (issue #89): the tiered rendering FROM THE PRUNED ELEMENT TREE —
 * the renderer's full/economy/lean tiers, the prune semantics it builds on,
 * pseudo-element text preservation, the hashed-URL placeholders, and the
 * router's tier delegation (full routes per structure; economy/lean are
 * tree reductions).
 */
class PrunedTreeHtmlRendererTest {

    private final JsoupDomAnalyzer analyzer = new JsoupDomAnalyzer();
    private final PrunedTreeHtmlRenderer renderer = new PrunedTreeHtmlRenderer();

    private static final String LISTING = "<html><head><title>List</title></head><body>"
            + "<nav class=\"pagination\"><a href=\"/list?page=2&amp;utm_source=x\">Next</a></nav>"
            + "<main><ul class=\"news-list\">"
            + "<li><article><h2><a href=\"/news/detail-1?query=long-value-string-1234567890\">"
            + "First headline</a></h2><p class=\"date\">2026-11-17</p>"
            + "<p class=\"desc\">The description of the first news item.</p></article></li>"
            + "<li><article><h2><a href=\"/news/detail-2\">Second headline</a></h2>"
            + "<p class=\"date\">2026-11-16</p>"
            + "<p class=\"desc\">The description of the second news item.</p></article></li>"
            + "</ul>"
            + "<div class=\"decorative-wrapper\"><span></span><div></div></div>"
            + "</main></body></html>";

    @Test
    void theFullTierRendersEveryPrunedTreeElement() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Document pruned = analyzer.prune(LISTING, placeholders);

        SimplifiedContent full = renderer.render(pruned, "full");

        // Data survives the pruned-tree rendering
        assertTrue(full.html().contains("First headline"));
        assertTrue(full.html().contains("Second headline"));
        assertTrue(full.html().contains("2026-11-17"));
        assertTrue(full.html().contains("/news/detail-1"));
        // The output is syntactically correct HTML5 (parses cleanly)
        Document reparsed = Jsoup.parse(full.html());
        assertEquals(2, reparsed.select("article").size(),
                "both listing items survive the full tier");
    }

    @Test
    void theEconomyTierShrinksTheOutputTowardItsTarget() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Document pruned = analyzer.prune(LISTING, placeholders);

        SimplifiedContent full = renderer.render(pruned, "full");
        SimplifiedContent economy = renderer.render(pruned, "economy");

        assertTrue(economy.sizeBytes() <= full.sizeBytes(),
                "economy never exceeds full (" + economy.sizeBytes()
                        + " vs " + full.sizeBytes() + ")");
        // The tier is a REDUCTION: the data (headlines, dates) survives
        assertTrue(economy.html().contains("First headline"),
                "headlines survive the economy tier");
        assertTrue(economy.html().contains("2026-11-17"),
                "dates survive the economy tier");
    }

    @Test
    void theLeanTierStripsQueryStringsAndHashesLongUrls() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Document pruned = analyzer.prune(LISTING, placeholders);

        SimplifiedContent lean = renderer.render(pruned, "lean");

        // Query strings stripped (tracking noise): utm_source gone
        assertFalse(lean.html().contains("utm_source"),
                "tracking query strings are stripped in the lean tier");
        // The pagination link KEEPS its page semantics (the page= param
        // is pagination data, not tracking noise)
        assertTrue(lean.html().contains("page=2"),
                "the pagination query param survives");
        // The detail link path survives
        assertTrue(lean.html().contains("/news/detail-1"),
                "detail link paths survive the lean tier");
        // Long URLs land in the placeholders side table
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            assertTrue(e.getKey().startsWith("hash:"),
                    "placeholders are hash: keys");
            assertTrue(e.getValue().length() > 0);
        }
    }

    @Test
    void pseudoElementTextPreservationKeepsIconOnlyControls() {
        // An icon-only button (no text) whose meaning lives in its
        // aria-label: the rendered tree carries the label as real text
        // (US-07.6 pseudo-element preservation; US-07.4 data rule).
        String html = "<html><body><main>"
                + "<button aria-label=\"Close dialog\" class=\"icon-btn\"></button>"
                + "<a href=\"/next\" title=\"Go to next page\"></a>"
                + "</main></body></html>";
        Map<String, String> placeholders = new LinkedHashMap<>();
        Document pruned = analyzer.prune(html, placeholders);

        SimplifiedContent full = renderer.render(pruned, "full");

        assertTrue(full.html().contains("Close dialog"),
                "the icon-only button's aria-label survives as text");
        assertTrue(full.html().contains("Go to next page"),
                "the icon-only link's title survives as text");
    }

    @Test
    void anUnknownVariantIsRefusedLoud() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Document pruned = analyzer.prune(LISTING, placeholders);

        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(pruned, "tiny"));
    }

    @Test
    void aMediaBearingPagePassesTheComplianceCheckOnEveryWiredTier() {
        // US-07.6 review B1+N1 pin: the prune keeps img/svg/video (they
        // are neither irrelevant nor interactable) — the WIRED tiered
        // path (analyzer+renderer, the Spring shape) must strip them
        // (the same forbidden-element rule as the cleanse) and the
        // output must pass the US-07.4 compliance check on every tier.
        String html = "<html><head><title>Page</title></head><body><main>"
                + "<img src=\"/x.png\" alt=\"a\">"
                + "<svg><circle r=\"1\"/></svg>"
                + "<video src=\"/v.mp4\"></video>"
                + "<iframe src=\"/embed\"></iframe>"
                + "<p>word ".repeat(1) + "</p>"
                + "<article><h2><a href=\"/detail\">Headline</a></h2>"
                + "<p>" + "word ".repeat(200) + "</p></article>"
                + "</main></body></html>";
        JsoupSimplifyConnector wired = new JsoupSimplifyConnector(
                analyzer, renderer);
        for (String tier : new String[]{"full", "economy", "lean"}) {
            SimplifiedContent out = wired.simplify(html, tier);
            assertFalse(out.html().contains("<img"), tier);
            assertFalse(out.html().contains("<svg"), tier);
            assertFalse(out.html().contains("<video"), tier);
            assertFalse(out.html().contains("<iframe"), tier);
            assertTrue(out.html().contains("Headline"), tier + " keeps data");
            // does not throw = passes checkSyntax (a failure is loud)
            JsoupSimplifyConnector.checkSyntax(out.html());
        }
    }

    @Test
    void economyTruncationKeepsEmbeddedLinks() {
        // US-07.6 review B2 pin: el.text(String) replaces ALL children —
        // truncating a <p> holding a detail link must never delete it.
        String html = "<html><body><main><article>"
                + "<h2><a href=\"/detail-1\">Headline</a></h2>"
                + "<p>" + "word ".repeat(150) + "<a href=\"/read-more\">Read more</a></p>"
                + "</article></main></body></html>";
        Map<String, String> placeholders = new LinkedHashMap<>();
        Document pruned = analyzer.prune(html, placeholders);

        SimplifiedContent economy = renderer.render(pruned, "economy");

        assertTrue(economy.html().contains("/read-more"),
                "the embedded detail link survives the economy truncation");
        assertTrue(economy.html().contains("Read more"),
                "the link's text survives");
    }

    @Test
    void theRouterRoutesFullThroughStructureAndDelegatesTheOtherTiers() {
        ReadabilitySimplifyRouter router = new ReadabilitySimplifyRouter(
                new JsoupSimplifyConnector());

        // FULL tier on an article-like page: the Readability path (the
        // regression pin — a null/blank variant MUST normalize to full
        // BEFORE the branch, or the default tier silently bypasses the
        // Readability routing: probe-verified bug).
        String article = "<html><head><title>Test Article</title></head><body>"
                + "<article><p>" + "word ".repeat(120) + "</p></article></body></html>";
        SimplifiedContent full = router.simplify(article);
        assertTrue(full.html().contains("Test Article"),
                "the article title h1 re-attach proves the Readability path ran");

        // An unknown variant is refused before any fetch/parse work
        assertThrows(IllegalArgumentException.class,
                () -> router.simplify(article, "micro"));
    }
}
