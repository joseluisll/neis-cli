package es.jllopez.neis.infrastructure.adapter.scrape;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // US-07.4 (issue #76): syntactic correctness and non-data exclusion.

    @Test
    void outputReparsesWithoutHtml5ParseErrorsOnRealFixtures() throws Exception {
        // The syntax check is part of simplify() itself: any output that
        // fails the HTML5 re-parse throws (a task-run error). These real
        // fixtures produce clean output — the assertion IS the check.
        for (String name : new String[]{
                "addr-001-news.html", "addr-002-events.html",
                "addr-003-news-spa-rendered.html", "addr-004-events-spa-rendered.html"}) {
            assertDoesNotThrow(() -> connector.simplify(fixture(name)),
                    name + " must simplify to parseable HTML5");
        }
    }

    @Test
    void checkSyntaxRejectsTruncatedMarkup() {
        // A truncated tag (the shape a broken cap-truncation would emit)
        // must be refused by the shared check — the guard is fail-closed.
        String broken = "<html><body><div class=\"x\"><p>text</p></div><a href=\"unfin";
        assertThrows(JsoupSimplifyConnector.SimplifySyntaxException.class,
                () -> JsoupSimplifyConnector.checkSyntax(broken),
                "truncated markup must fail the HTML5 syntax check");
    }

    @Test
    void checkSyntaxRejectsForbiddenElements() {
        for (String snippet : new String[]{
                "<html><body><p>ok</p><script>alert(1)</script></body></html>",
                "<html><body><style>.a{}</style><p>ok</p></body></html>",
                "<html><body><iframe src=\"x\"></iframe></body></html>",
                "<html><body><img src=\"x.png\" alt=\"a\"></body></html>",
                "<html><body><video src=\"x.mp4\"></video></body></html>",
                "<html><body><object data=\"x\"></object></body></html>",
                "<html><body><embed src=\"x\"></body></html>",
                "<html><body><template><p>t</p></template></body></html>"}) {
            assertThrows(JsoupSimplifyConnector.SimplifySyntaxException.class,
                    () -> JsoupSimplifyConnector.checkSyntax(snippet),
                    "forbidden non-data element must be refused: " + snippet);
        }
        assertDoesNotThrow(
                () -> JsoupSimplifyConnector.checkSyntax(
                        "<html><body><p>ok</p><a href=\"/l\">Link</a></body></html>"),
                "data/action elements must pass the check");
    }

    // US-07.4 review round-1 findings B1/B2 fixes.

    @Test
    void simplifyHandlesEveryForbiddenElementWithoutFailingTheRun() {
        // B1: a forbidden element must be REMOVED (or unwrapped), never
        // left in place to fail the whole run — failing the run loses ALL
        // the data, which breaks the data-preservation rule.
        for (String element : new String[]{
                "<script>alert(1)</script>", "<style>.a{}</style>",
                "<link rel=\"stylesheet\" href=\"x.css\">", "<meta charset=\"x\">",
                "<noscript>fallback</noscript>", "<iframe src=\"x\"></iframe>",
                "<frame src=\"x\">", "<applet></applet>", "<object data=\"x\"></object>",
                "<embed src=\"x\">", "<img src=\"x.png\">", "<picture></picture>",
                "<source src=\"x\">", "<video src=\"x.mp4\"></video>",
                "<audio src=\"x.mp3\"></audio>", "<track src=\"x\">",
                "<canvas></canvas>", "<map name=\"m\"></map>", "<area href=\"x\">",
                "<noembed>x</noembed>", "<param name=\"p\" value=\"v\">"}) {
            String raw = "<html><body><h1>Keep</h1>" + element
                    + "<p>Data stays</p></body></html>";
            var out = assertDoesNotThrow(() -> connector.simplify(raw),
                    "simplify must handle a forbidden element, not fail: " + element);
            assertTrue(out.html().contains("Keep"), element);
            assertTrue(out.html().contains("Data stays"), element);
        }
    }

    @Test
    void popupDeclaredDialogIsRemovedWithData() {
        // Round-2 review finding B3: a dialog that DECLARES itself a
        // popup (class/aria-label/id) is removed WITH its content —
        // unwrapping it would promote the popup text into the page.
        String raw = "<html><body>"
                + "<dialog id=\"helpdeskModal\" class=\"modal\"><p>helpdesk</p></dialog>"
                + "<dialog aria-label=\"Newsletter popup\"><p>subscribe</p></dialog>"
                + "<template class=\"popup-template\"><p>hidden ad</p></template>"
                + "<h1>Main</h1><p>Content stays</p></body></html>";
        String simplified = connector.simplify(raw).html();
        assertFalse(simplified.contains("helpdesk"), "popup dialog content must go");
        assertFalse(simplified.contains("subscribe"),
                "aria-label-declared popup content must go");
        assertFalse(simplified.contains("hidden ad"), "popup template must go");
        assertTrue(simplified.contains("Main"));
        assertTrue(simplified.contains("Content stays"));
    }

    @Test
    void dialogAndTemplateAreUnwrappedTheirDataSurvives() {
        // B1: dialog/template are forbidden (popups/dynamic content) but
        // may wrap real data — unwrapped, not removed.
        String raw = "<html><body>"
                + "<dialog open><article><h2>Popup title</h2>"
                + "<p>Popup body</p></article></dialog>"
                + "<template><p>Templated note</p></template>"
                + "<h1>Main</h1></body></html>";
        String simplified = connector.simplify(raw).html();
        assertFalse(simplified.contains("<dialog"), "the dialog element is gone");
        assertFalse(simplified.contains("<template"), "the template element is gone");
        assertTrue(simplified.contains("Popup title"),
                "data inside the dialog survives the unwrap");
        assertTrue(simplified.contains("Popup body"));
        assertTrue(simplified.contains("Templated note"),
                "data inside the template survives the unwrap");
        assertTrue(simplified.contains("Main"));
    }

    @Test
    void advertisementsAndPopupsAreRemoved() {
        // B2: ads and popups are excluded by the requirement.
        String raw = "<html><body>"
                + "<ins class=\"adsbygoogle\">ad slot</ins>"
                + "<div class=\"advertisement\">BUY NOW</div>"
                + "<div class=\"advert-banner\">promo</div>"
                + "<div class=\"newsletter-popup\">subscribe</div>"
                + "<p>Real content</p></body></html>";
        String simplified = connector.simplify(raw).html();
        assertFalse(simplified.contains("adsbygoogle"), "ads must go");
        assertFalse(simplified.contains("BUY NOW"), "advertisement containers must go");
        assertFalse(simplified.contains("promo"), "advert-* fragments must go");
        assertFalse(simplified.contains("subscribe"), "popups must go");
        assertTrue(simplified.contains("Real content"));
    }

    @Test
    void javascriptUrlsAreNeutralisedElementsKept() {
        // B2: a javascript: URL is dynamic code; the attribute goes, the
        // element (an action element) and its text stay.
        String raw = "<html><body>"
                + "<a href=\"javascript:alert(1)\">Click</a>"
                + "<form action=\"javascript:x()\"><button formaction=\"javascript:y()\">Go</button></form>"
                + "<p>Stays</p></body></html>";
        String simplified = connector.simplify(raw).html();
        assertFalse(simplified.contains("javascript:"), "no javascript: URL survives");
        assertTrue(simplified.contains("Click"), "the link's text survives");
        assertTrue(simplified.contains("<a"), "the link element survives");
        assertTrue(simplified.contains("<button"), "the button survives");
        assertTrue(simplified.contains("Go"), "the button's label survives");
    }

    @Test
    void checkSyntaxRejectsJavascriptUrls() {
        assertThrows(JsoupSimplifyConnector.SimplifySyntaxException.class,
                () -> JsoupSimplifyConnector.checkSyntax(
                        "<html><body><a href=\"javascript:alert(1)\">x</a></body></html>"),
                "the tripwire must refuse javascript: URLs");
        assertDoesNotThrow(
                () -> JsoupSimplifyConnector.checkSyntax(
                        "<html><body><a href=\"https://example.org/\">x</a></body></html>"),
                "real URLs pass");
    }

    @Test
    void dropsEventHandlerAttributes() {
        // Event handlers are dynamic code (US-07.4): every on* attribute
        // goes, the element and its data stay.
        String raw = "<html><body onload=\"boot()\">"
                + "<button onclick=\"go()\" onmouseover=\"hi()\">Go</button>"
                + "<p>Text stays</p></body></html>";
        String simplified = connector.simplify(raw).html();
        assertFalse(simplified.contains("onclick="), "onclick must go");
        assertFalse(simplified.contains("onmouseover="), "onmouseover must go");
        assertFalse(simplified.contains("onload="), "onload must go");
        assertTrue(simplified.contains("<button"), "the button (action element) stays");
        assertTrue(simplified.contains("Go"), "the button label (data) stays");
        assertTrue(simplified.contains("Text stays"));
    }

    @Test
    void simplifyOutputOfFixtureContainsNoForbiddenElements() throws Exception {
        // The exclusion rule pinned end to end: re-parse the real
        // fixtures' simplified output and assert none of the forbidden
        // element names survives (an exact-name check, mirroring the
        // connector's Set — a substring test would false-match).
        java.util.Set<String> forbidden = java.util.Set.of(
                "script", "style", "iframe", "object", "embed", "svg",
                "img", "video", "audio", "canvas", "dialog", "template");
        for (String name : new String[]{
                "addr-001-news.html", "addr-002-events.html",
                "addr-003-news-spa-rendered.html", "addr-004-events-spa-rendered.html"}) {
            String html = connector.simplify(fixture(name)).html();
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            for (org.jsoup.nodes.Element el : doc.select("*")) {
                assertFalse(forbidden.contains(el.tagName().toLowerCase()),
                        name + ": forbidden element <" + el.tagName() + "> survived");
            }
        }
    }
}
