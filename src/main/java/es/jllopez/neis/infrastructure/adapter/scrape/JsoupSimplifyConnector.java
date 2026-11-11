package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.SimplifiedContent;
import es.jllopez.neis.domain.port.SimplifyPort;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Connector (driven adapter) implementing the SIMPLIFY port with jsoup
 * (owner-confirmed stack, US-07.1/US-07.2): strips visual styles,
 * multimedia and structural noise, keeps action and data elements, and
 * caps the output at 100 KB (US-07.3).
 */
@Component
public class JsoupSimplifyConnector implements SimplifyPort {

    /** Output cap, US-07.3. */
    static final int MAX_OUTPUT_BYTES = 100 * 1024;

    private static final String STRIPPED_TAGS =
            "script, style, link, meta, noscript, iframe, svg, img, video, audio, "
                    + "source, picture, canvas, object, embed, map, area, "
                    // structural/layout-only containers (US-07.1)
                    + "header, nav, footer, aside";

    private static final String[] STRIPPED_ATTRIBUTES = {
            "style", "srcset", "sizes", "media", "onload", "onclick", "data-src",
            "data-image", "background"};

    /**
     * Structural/layout-only class fragments (US-07.1): page trees,
     * sidebars, breadcrumbs, menus, toolbars, cookie banners and similar
     * chrome that carries no news/event data.
     */
    private static final String[] STRIPPED_CLASS_FRAGMENTS = {
            "pagetree", "sidebar", "breadcrumb", "menu", "toolbar", "cookie",
            "banner", "modal", "tooltip", "search", "sr-only", "screen-reader",
            "skiplink", "a11y"};

    @Override
    public SimplifiedContent simplify(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);
        doc.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);

        doc.select(STRIPPED_TAGS).remove();
        doc.select("head").remove(); // head carries metadata, not action/data elements
        doc.select("[aria-hidden=true]").remove(); // decorative/hidden chrome
        for (String fragment : STRIPPED_CLASS_FRAGMENTS) {
            doc.select("[class*=" + fragment + "]").remove();
        }
        for (Element el : doc.select("*")) {
            for (String attr : STRIPPED_ATTRIBUTES) {
                el.removeAttr(attr);
            }
        }
        removeComments(doc.body());

        String html = doc.html();
        if (sizeBytes(html) > MAX_OUTPUT_BYTES) {
            html = truncateUnderCap(html);
        }
        return new SimplifiedContent(html, sizeBytes(html));
    }

    private void removeComments(Node node) {
        for (Node child : new ArrayList<>(node.childNodes())) {
            if (child instanceof org.jsoup.nodes.Comment
                    || child instanceof org.jsoup.nodes.DataNode) {
                child.remove();
            } else {
                removeComments(child);
            }
        }
    }

    /**
     * Hard cap: cut at the last tag boundary and let jsoup re-parse to
     * close the broken markup (US-07.6 is later scope); iterate because
     * re-serialization can expand entities, until the output fits.
     */
    private String truncateUnderCap(String html) {
        String candidate = html;
        for (int attempt = 0; attempt < 4 && sizeBytes(candidate) > MAX_OUTPUT_BYTES;
                attempt++) {
            int budget = Math.max(1024, MAX_OUTPUT_BYTES - 2048 * (attempt + 1));
            String cut = candidate.substring(0, Math.min(candidate.length(), budget));
            int lastClose = cut.lastIndexOf('>');
            if (lastClose > 0) {
                cut = cut.substring(0, lastClose + 1);
            }
            Document repaired = Jsoup.parse(cut);
            repaired.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
            candidate = repaired.html();
        }
        return candidate;
    }

    private int sizeBytes(String html) {
        return html.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Exposed for tests/inspection: element counts of a simplified page. */
    public Elements select(String simplifiedHtml, String cssSelector) {
        Document doc = Jsoup.parse(simplifiedHtml);
        return doc.select(cssSelector);
    }
}
