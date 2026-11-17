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
}
