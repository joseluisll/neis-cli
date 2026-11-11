package es.jllopez.neis.infrastructure.adapter.scrape;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIMPLIFY connector behaviour (requirements US-07.1..US-07.3).
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
    void stripsStylesScriptsAndComments() throws Exception {
        String simplified = connector.simplify(fixture("addr-001-news.html")).html();
        assertFalse(simplified.contains("<script"));
        assertFalse(simplified.contains("<style"));
        assertFalse(simplified.contains(".aui-style-"), "visual CSS must be stripped");
        assertFalse(simplified.contains("<!--"), "comments must be stripped");
    }

    @Test
    void outputCappedAt100KbAndContentKept() throws Exception {
        var simplified = connector.simplify(fixture("addr-001-news.html"));
        assertTrue(simplified.sizeBytes() <= JsoupSimplifyConnector.MAX_OUTPUT_BYTES,
                "simplified output must respect the 100 KB cap (US-07.3)");
        assertTrue(simplified.html().contains("news entry 1"),
                "data elements (news items) must survive SIMPLIFY");
    }

    @Test
    void keepsLinksAndHeadingsFromFixtures() throws Exception {
        String simplified = connector.simplify(fixture("addr-003-news-spa-rendered.html")).html();
        assertTrue(simplified.contains("Support News"));
        assertTrue(simplified.contains("href="));
        assertFalse(simplified.contains("<script"));
        assertFalse(simplified.contains("<style"));
    }
}
