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

    @Test
    void keepsPaginationNavsButStripsOtherChrome() {
        String html = "<html><body>"
                + "<nav class=\"main-menu\"><a href=\"/home\">Menu</a></nav>"
                + "<p>Content</p>"
                + "<nav class=\"ecl-pagination\" aria-label=\"Pagination\">"
                + "<a href=\"?page=1\">2</a><a href=\"?page=1\">Next</a></nav>"
                + "</body></html>";
        String out = connector.simplify(html).html();
        assertFalse(out.contains("main-menu"), "menu navs are page chrome");
        assertTrue(out.contains("ecl-pagination"),
                "pagination navs are action elements the capability detection needs");
        assertTrue(out.contains("?page=1"));
    }

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
    void keepsActionAndDataElements() {
        // US-07.2: the result is reduced to action and data elements —
        // forms, buttons, links, inputs, tables, lists, headings and
        // text survive; the style/script/media noise around them does
        // not (pinned per the acceptance criteria).
        String raw = """
                <html><body>
                <nav class="site-nav"><a href="/menu">Menu</a></nav>
                <form action="/filter"><select name="year"><option value="2026">2026</option></select>
                <input type="checkbox" name="flag"><button type="submit">Go</button></form>
                <table><tr><td>Cell data</td></tr></table>
                <ul><li>List item</li></ul>
                <h2>Section heading</h2>
                <p>Paragraph text</p>
                </body></html>""";
        String simplified = connector.simplify(raw).html();

        assertTrue(simplified.contains("<form"), "forms are action elements");
        assertTrue(simplified.contains("<select"), "selects are action elements");
        assertTrue(simplified.contains("<option"), "options carry the values");
        assertTrue(simplified.contains("<input"), "inputs are action elements");
        assertTrue(simplified.contains("<button"), "buttons are action elements");
        assertTrue(simplified.contains("<table"), "tables are data elements");
        assertTrue(simplified.contains("Cell data"));
        assertTrue(simplified.contains("<li>List item</li>"), "lists are data elements");
        assertTrue(simplified.contains("<h2>Section heading</h2>"), "headings are kept");
        assertTrue(simplified.contains("Paragraph text"));
        // Functional attributes must survive with their elements: the
        // downstream readers depend on them (href/datetime by extraction,
        // rel=next/aria-label by pagination detection) — a future edit
        // wrongly stripping 'action' or 'name' must fail here
        // (US-07 review finding, 2026-11-14).
        assertTrue(simplified.contains("action=\"/filter\""), "form targets survive");
        assertTrue(simplified.contains("name=\"year\""), "select/input names survive");
        assertTrue(simplified.contains("value=\"2026\""), "option values survive");
        // The class fragment 'site-nav' matches no STRIPPED_CLASS_FRAGMENTS
        // entry, so this pins the nav-ELEMENT handling in isolation
        // (US-07 review finding, 2026-11-14).
        assertFalse(simplified.contains("<nav"), "chrome navs are stripped");
        assertFalse(simplified.contains("/menu"), "the nav's links go with it");
    }

    @Test
    void dropsPresentationAttributes() {
        // US-07.2: presentation attributes and inline styles are dropped
        // — the data survives, the looks do not.
        String raw = """
                <html><body>
                <table border="1" cellpadding="2" bgcolor="#ffffff" width="100%">
                <tr align="center" valign="top"><td height="20">Data cell</td></tr></table>
                <div align="right" style="font-weight:bold">Styled text</div>
                </body></html>""";
        String simplified = connector.simplify(raw).html();

        assertFalse(simplified.contains("border="));
        assertFalse(simplified.contains("cellpadding="));
        assertFalse(simplified.contains("bgcolor="));
        assertFalse(simplified.contains("width="));
        assertFalse(simplified.contains("align="));
        assertFalse(simplified.contains("valign="));
        assertFalse(simplified.contains("height="));
        assertFalse(simplified.contains("style="));
        assertTrue(simplified.contains("Data cell"), "the data survives");
        assertTrue(simplified.contains("Styled text"), "the text survives");
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

    @Test
    void dropsCheckboxClonesOfSelectOptionsInFilterWidgets() {
        // Multi-select filter widgets render every option twice (native
        // select options + generated checkbox/label clones). The clones
        // are duplication: they must go, the select with its values stays,
        // and content after the form survives the cap.
        String option = "<option value=\"%d\">Programme %d</option>";
        String clone = "<div class=\"ecl-checkbox\"><input type=\"checkbox\" "
                + "id=\"cb%d\"><label for=\"cb%d\">Programme %d</label></div>";
        StringBuilder form = new StringBuilder("<form id=\"facets\"><select multiple>");
        StringBuilder clones = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            form.append(String.format(option, i, i));
            clones.append(String.format(clone, i, i, i));
        }
        form.append("</select>").append(clones).append("</form>");
        String raw = "<html><body>" + form
                + "<article class=\"item\">Real listing item</article></body></html>";
        String simplified = connector.simplify(raw).html();

        assertFalse(simplified.contains("type=\"checkbox\""),
                "checkbox clones of select options must be dropped");
        assertTrue(simplified.contains("<option"), "the select options must stay");
        assertTrue(simplified.contains("Real listing item"),
                "content after the form must survive the cap");
    }

    @Test
    void keepsCheckboxFiltersThatAreNotSelectClones() {
        // A standalone checkbox facet with no select twin is a REAL filter
        // field — it must survive the dedup.
        String raw = "<html><body><form id=\"facets\">"
                + "<select name=\"topics\"><option value=\"1\">Cancer</option></select>"
                + "<div class=\"ecl-checkbox\"><input type=\"checkbox\" id=\"x\">"
                + "<label for=\"x\">Patient safety</label></div>"
                + "</form></body></html>";
        String simplified = connector.simplify(raw).html();
        assertTrue(simplified.contains("Patient safety"),
                "a checkbox filter that mirrors no select option stays");
    }
}
