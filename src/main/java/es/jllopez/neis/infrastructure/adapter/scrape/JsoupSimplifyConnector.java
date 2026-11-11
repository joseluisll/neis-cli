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
                    + "source, picture, canvas, object, embed, map, area";

    private static final String[] STRIPPED_ATTRIBUTES = {
            "style", "srcset", "sizes", "media", "onload", "onclick", "data-src",
            "data-image", "background"};

    @Override
    public SimplifiedContent simplify(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);
        doc.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);

        doc.select(STRIPPED_TAGS).remove();
        doc.select("head").remove(); // head carries metadata, not action/data elements
        for (Element el : doc.select("*")) {
            for (String attr : STRIPPED_ATTRIBUTES) {
                el.removeAttr(attr);
            }
        }
        removeComments(doc.body());

        String html = doc.html();
        if (sizeBytes(html) > MAX_OUTPUT_BYTES) {
            html = truncateAtElementBoundary(html);
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
     * Hard cap: cut at the last tag boundary under the limit and let
     * jsoup re-parse to close the broken markup (US-07.6 is later scope;
     * this keeps the artifact syntactically well-formed).
     */
    private String truncateAtElementBoundary(String html) {
        String cut = html.substring(0, MAX_OUTPUT_BYTES);
        int lastClose = cut.lastIndexOf('>');
        if (lastClose > 0) {
            cut = cut.substring(0, lastClose + 1);
        }
        Document repaired = Jsoup.parse(cut);
        repaired.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
        return repaired.html();
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
