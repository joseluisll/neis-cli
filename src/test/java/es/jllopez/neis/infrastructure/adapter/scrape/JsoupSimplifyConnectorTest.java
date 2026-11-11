package es.jllopez.neis.infrastructure.adapter.scrape;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIMPLIFY connector behaviour (requirements US-07.1..US-07.3) — against
 * the real Playwright-rendered fixtures of issue #62 and synthetic
 * inputs for the deterministic cap cases.
 */
class JsoupSimplifyConnectorTest {

    private final JsoupSimplifyConnector connector = new JsoupSimplifyConnector();

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/mvp/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void stripsStylesScriptsMediaAndComments() {
        String raw = """
                <html><head><title>t</title><style>.x{color:red}</style></head>
                <body><!-- a comment -->
                <img src="x.png"><script>var a = 1;</script>
                <h1 class="keep">Title</h1><a href="/l">Link</a><p style="color:blue">Text</p>
                </body></html>""";
        String simplified = connector.simplify(raw).html();
        assertFalse(simplified.contains("<script"));
        assertFalse(simplified.contains("<style"));
        assertFalse(simplified.contains("<img"));
        assertFalse(simplified.contains("<!--"));
        assertFalse(simplified.contains("color:red"));
        assertTrue(simplified.contains("Title"));
        assertTrue(simplified.contains("href=\"/l\""));
    }

    @Test
    void capsSyntheticOversizedInputAt100Kb() {
        String filler = "<p class=\"filler\">" + "x".repeat(128) + "</p>".repeat(1200);
        String raw = "<html><body><h1>Head</h1>" + filler + "</body></html>";
        var simplified = connector.simplify(raw);
        assertTrue(simplified.sizeBytes() <= JsoupSimplifyConnector.MAX_OUTPUT_BYTES,
                "US-07.3: output must stay within the 100 KB cap, got "
                        + simplified.sizeBytes());
        assertTrue(simplified.html().contains("Head"));
    }

    @Test
    void realRenderedFixturesSimplifyUnderTheCap() throws Exception {
        // The real Playwright-rendered seed pages are 348-548 KB of mixed
        // Angular/Confluence markup; SIMPLIFY must bring them under 100 KB.
        for (String name : new String[]{
                "addr-001-news.html", "addr-002-events.html",
                "addr-003-news-spa-rendered.html", "addr-004-events-spa-rendered.html"}) {
            var simplified = connector.simplify(fixture(name));
            assertTrue(simplified.sizeBytes() <= JsoupSimplifyConnector.MAX_OUTPUT_BYTES,
                    name + " must simplify under the cap, got " + simplified.sizeBytes());
            assertFalse(simplified.html().contains("<script"), name);
            assertFalse(simplified.html().contains("<style"), name);
        }
    }

    @Test
    void keepsRenderedNewsContentFromRealFixture() throws Exception {
        // ADDR-003 rendered DOM carries the news result cards; their links
        // and text must survive SIMPLIFY for the AI/selector steps.
        String simplified = connector.simplify(fixture("addr-003-news-spa-rendered.html")).html();
        assertTrue(simplified.contains("support/news/"), "news detail links must survive");
    }
}
