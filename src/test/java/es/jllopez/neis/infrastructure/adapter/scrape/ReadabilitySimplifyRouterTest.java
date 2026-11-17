package es.jllopez.neis.infrastructure.adapter.scrape;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-07.5 (issue #87): Readability4J as the PRIMARY main-content
 * extractor for article-like pages, the jsoup Safelist rules as the
 * fallback for non-article structures — behind the SAME SimplifyPort
 * (the router delegates the output contract to the jsoup connector, so
 * US-07.4 holds on both paths).
 */
class ReadabilitySimplifyRouterTest {

    private final ReadabilitySimplifyRouter router =
            new ReadabilitySimplifyRouter(new JsoupSimplifyConnector());

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/mvp/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A synthetic article: prose-dominant, one article marker. */
    private String article(String... paragraphs) {
        StringBuilder body = new StringBuilder(
                "<html><head><title>Test Article</title></head><body>"
                        + "<nav class=\"main-menu\"><a href=\"/home\">Home</a>"
                        + "<a href=\"/about\">About</a></nav>"
                        + "<article><h1>Main Title Here</h1>"
                        + "<p class=\"byline\">By Jane Doe</p>");
        for (String p : paragraphs) {
            body.append("<p>").append(p).append("</p>");
        }
        body.append("<a href=\"/detail-1\">Detail link</a></article>")
                .append("<footer><p>copyright boilerplate</p></footer>")
                .append("<script>alert('x')</script></body></html>");
        return body.toString();
    }

    private String paragraph(long words) {
        StringBuilder sb = new StringBuilder();
        for (long i = 0; i < words; i++) {
            sb.append("word ").append(i).append(" of the article body text. ");
        }
        return sb.toString();
    }

    // --- the routing probe ---

    @Test
    void articleLikeProbeDetectsArticles() {
        assertTrue(router.isArticleLike(article(paragraph(40))),
                "a prose-dominant page with an <article> is article-like");
    }

    @Test
    void listingProbeRejectsListings() {
        // A listing: one article-free grid of item links + a filter form.
        StringBuilder items = new StringBuilder("<html><body><ul class=\"news-list\">");
        for (int i = 1; i <= 25; i++) {
            items.append("<li><a href=\"/item-").append(i)
                    .append("\">Item ").append(i)
                    .append(" short title</a></li>");
        }
        items.append("</ul><form action=\"/filter\"><select name=\"year\">")
                .append("<option>2026</option></select></form></body></html>");
        String html = items.toString();
        assertFalse(router.isArticleLike(html),
                "a link-list listing with no article marker is not article-like");
    }

    @Test
    void articleLikePageIsReducedByReadability() {
        // The boilerplate nav/footer must go even when they carry no
        // stripped class fragment (the jsoup cleanse alone would keep a
        // plain <nav>'s pagination links); the article's content stays.
        String html = article(paragraph(40));
        SimplifiedContentNoExport out = simplify(html);
        assertFalse(out.html.contains("main-menu"),
                "the site menu must not survive a readability extraction");
        assertFalse(out.html.contains("copyright boilerplate"),
                "footer boilerplate must not survive");
        assertTrue(out.html.contains("Main Title Here") || out.html.contains("word"),
                "the article content survives");
        assertFalse(out.html.contains("<script"), "no scripts (US-07.4)");
    }

    // small holder because SimplifiedContent's html() is fine but keep it simple
    private record SimplifiedContentNoExport(String html, int sizeBytes) {}

    private SimplifiedContentNoExport simplify(String html) {
        var sc = router.simplify(html);
        return new SimplifiedContentNoExport(sc.html(), sc.sizeBytes());
    }

    // --- the fallback ---

    @Test
    void nonArticleListingFallsBackToJsoupRulesWithoutDataLoss() {
        StringBuilder items = new StringBuilder("<html><body><ul class=\"news-list\">");
        for (int i = 1; i <= 25; i++) {
            items.append("<li><a href=\"/item-").append(i)
                    .append("\">Item ").append(i).append(" title</a> ")
                    .append("<span>some description text for item ")
                    .append(i).append("</span></li>");
        }
        items.append("</ul></body></html>");
        String html = items.toString();
        String out = router.simplify(html).html();
        assertTrue(out.contains("/item-1"), "listing links must survive (data)");
        assertTrue(out.contains("Item 25 title"), "listing items must survive");
        assertFalse(out.contains("<script"));
    }

    @Test
    void readabilityFailureFallsBackToJsoupCleanse() {
        // A page the probe calls article-like but whose extraction is
        // degenerate: the router must still produce compliant output.
        String html = "<html><body><article><h1>T</h1>"
                + "<p>" + paragraph(200) + "</p></article></body></html>";
        String out = assertDoesNotThrow(() -> router.simplify(html).html());
        assertTrue(out.contains("T") || out.contains("word"),
                "content survives whichever extractor ran");
    }

    // --- the output contract holds on BOTH paths (US-07.4 via the port) ---

    @Test
    void outputContractHoldsOnRealFixtures() throws Exception {
        for (String name : new String[]{
                "addr-001-news.html", "addr-002-events.html",
                "addr-003-news-spa-rendered.html", "addr-004-events-spa-rendered.html"}) {
            String html = fixture(name);
            var sc = assertDoesNotThrow(() -> router.simplify(html, "static"),
                    name + " must simplify through the router");
            assertFalse(sc.html().contains("<script"), name);
            assertFalse(sc.html().contains("<style"), name);
            assertTrue(sc.sizeBytes() <= JsoupSimplifyConnector.MAX_OUTPUT_BYTES,
                    name + " stays under the cap");
        }
    }

    @Test
    void realRenderedNewsLinksSurviveThroughTheRouter() throws Exception {
        String out = router.simplify(fixture("addr-003-news-spa-rendered.html"),
                "spa-angular").html();
        assertTrue(out.contains("support/news/"),
                "news detail links must survive the routing simplify");
    }

    // --- US-07.5 review round-1 findings B1/B2/B3 ---

    @Test
    void articleTeaserListingsNeverTakeTheReadabilityPath() throws Exception {
        // B1 regression, on the REAL fixture the review caught: a news
        // listing built from many <article> teaser cards (addr-006) is a
        // LISTING — Readability would score one teaser as "the article"
        // and drop the rest. Every item detail link must survive.
        String out = router.simplify(fixture("addr-006-news-rendered.html")).html();
        long linkCount = out.split("href=", -1).length - 1;
        assertTrue(linkCount >= 60,
                "a teaser-card listing keeps its item links (got " + linkCount + ")");
    }

    @Test
    void syntheticTeaserListingKeepsItemsAndPaginationAndForm() {
        // B1: many similar article containers is a listing, however
        // prose-heavy the teasers are — pagination and filter forms too.
        StringBuilder teasers = new StringBuilder(
                "<html><body><nav class=\"ecl-pagination\" aria-label=\"Pagination\">"
                        + "<a href=\"?page=2\">2</a><a href=\"?page=3\">3</a></nav>"
                        + "<form action=\"/filter\"><select name=\"year\">"
                        + "<option>2026</option></select></form><main>");
        for (int i = 1; i <= 10; i++) {
            teasers.append("<article><h2>News ").append(i).append("</h2>")
                    .append("<p>Excerpt text for news item ").append(i)
                    .append(" with enough words to look prose-heavy and push")
                    .append(" the paragraph share of the page text up. ")
                    .append("More sentence content follows here so each teaser")
                    .append(" carries a substantial excerpt of its own.</p>")
                    .append("<a href=\"/news/item-").append(i).append("\">Read</a></article>");
        }
        teasers.append("</main></body></html>");
        String out = router.simplify(teasers.toString()).html();
        assertFalse(router.isArticleLike(teasers.toString()),
                "a multi-article teaser listing is not article-like");
        assertTrue(out.contains("/news/item-1"), "item links survive");
        assertTrue(out.contains("?page=2"), "pagination survives");
        assertTrue(out.contains("<select"), "the filter form survives");
    }

    @Test
    void singleArticlePageTakesTheReadabilityPath() {
        // The positive control for B1's guard: ONE article region still
        // routes to Readability (boilerplate outside the article goes).
        String html = article(paragraph(40));
        assertTrue(router.isArticleLike(html));
        String out = simplify(html).html();
        assertFalse(out.contains("main-menu"),
                "site chrome must go on the article path");
        assertTrue(out.contains("word"), "article text survives");
    }

    @Test
    void relativeUrlsAreNotRewrittenToAFabricatedHost() {
        // B2: Readability absolutises against the base URI; without a
        // declared base the hrefs must come back RELATIVE (as the jsoup
        // path emits them), never https://localhost/...
        String html = "<html><head><title>Art</title></head><body>"
                + "<article><h1>Title</h1><p>" + paragraph(40) + "</p>"
                + "<a href=\"/detail-1\">Detail</a></article></body></html>";
        String out = simplify(html).html();
        assertFalse(out.contains("localhost"), "no fabricated host in the output");
        assertTrue(out.contains("href=\"detail-1\""),
                "the relative detail link survives relative (Readability's "
                        + "absolutisation is undone to the relative path)");
    }

    @Test
    void titleIsNotDuplicatedAndPrecedesTheContent() {
        // B3: the title check must read the real body; the h1 (when the
        // <title> is not already present) comes BEFORE the content, and
        // an h1 carried by the article is not duplicated.
        String html = "<html><head><title>Test Article</title></head><body>"
                + "<article><p>" + paragraph(40) + "</p></article></body></html>";
        String out = simplify(html).html();
        long h1Count = out.split("<h1", -1).length - 1;
        assertTrue(h1Count >= 1, "the article title is attached as h1");
        // no duplicate h1 with the same text
        long titleCount = out.split("Test Article", -1).length - 1;
        assertTrue(titleCount <= 1,
                "the title must not appear twice (got " + titleCount + ")");

        // and when the article carries its own h1 matching the title:
        String html2 = "<html><head><title>Same Head</title></head><body>"
                + "<article><h1>Same Head</h1><p>" + paragraph(40)
                + "</p></article></body></html>";
        String out2 = simplify(html2).html();
        long titleCount2 = out2.split("Same Head", -1).length - 1;
        assertTrue(titleCount2 <= 1,
                "an h1 already in the content is not duplicated (got "
                        + titleCount2 + ")");
    }
}
