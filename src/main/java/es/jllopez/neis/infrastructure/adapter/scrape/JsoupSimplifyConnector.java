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
import java.util.HashSet;
import java.util.Set;

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

    /**
     * US-07.4: elements whose presence makes the output non-compliant —
     * dynamic code (scripts, embedded objects), presentation, media and
     * popup/iframe content. An exact-name Set, never a substring test
     * (a {@code p} selector must not match {@code template}).
     */
    private static final java.util.Set<String> FORBIDDEN_TAGS =
            java.util.Set.of("script", "style", "link", "meta", "noscript",
                    "iframe", "frame", "frameset", "object", "embed", "applet",
                    "svg", "img", "picture", "source", "video", "audio",
                    "track", "canvas", "map", "area", "dialog", "template");

    private static final String STRIPPED_TAGS =
            "script, style, link, meta, noscript, iframe, svg, img, video, audio, "
                    + "source, picture, canvas, object, embed, map, area, "
                    // structural/layout-only containers (US-07.1); nav is
                    // handled separately below (pagination navs are kept)
                    + "header, footer, aside";

    private static final String[] STRIPPED_ATTRIBUTES = {
            "style", "srcset", "sizes", "media", "onload", "onclick", "data-src",
            "data-image", "background",
            // presentation attributes (US-07.2: drop presentation
            // attributes and inline styles — the result carries data,
            // not looks). First batch: legacy attributes beyond these
            // (body text/link/vlink/alink, nowrap, char/charoff,
            // bordercolor) may be added as real pages require.
            "align", "valign", "bgcolor", "border", "cellpadding",
            "cellspacing", "width", "height", "frame", "rules", "clear",
            "hspace", "vspace"};

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
        // Nav elements are page chrome (menus, breadcrumbs) — EXCEPT
        // pagination navs: their next/page links are action elements the
        // ANALYZE capability detection needs (US-17).
        doc.select("nav").stream()
                .filter(nav -> !isPaginationNav(nav))
                .forEach(node -> node.remove());
        // Decorative/hidden chrome — but never the ECL date-block parts,
        // which are aria-hidden yet carry the VISIBLE rendered date.
        doc.select("[aria-hidden=true]:not(.ecl-date-block__day)"
                + ":not(.ecl-date-block__month):not(.ecl-date-block__year)").remove();
        for (String fragment : STRIPPED_CLASS_FRAGMENTS) {
            for (Element el : doc.select("[class*=" + fragment + "]")) {
                // Filter UI (form with select dropdowns) and listing items
                // (article elements) are action/data content (US-07.2) —
                // often carrying misleading class fragments ("search" in
                // EMA's "card--variant-search" teasers). Keep them so the
                // ANALYZE capability detection, the FILTER block and the
                // extraction still see them; strip plain chrome as before.
                if (el.selectFirst("form select") != null
                        || el.selectFirst("article") != null) {
                    continue;
                }
                el.remove();
            }
        }
        dropDuplicatedWidgetCheckboxEntries(doc);
        for (Element el : doc.select("*")) {
            for (String attr : STRIPPED_ATTRIBUTES) {
                el.removeAttr(attr);
            }
        }
        removeEventHandlers(doc);
        removeComments(doc.body());

        String html = doc.html();
        if (sizeBytes(html) > MAX_OUTPUT_BYTES) {
            html = truncateUnderCap(html);
        }
        // US-07.4: the output MUST parse as valid HTML5 — the check runs
        // on the exact bytes the caller stores; a failed check is a task
        // error, not silence.
        checkSyntax(html);
        return new SimplifiedContent(html, sizeBytes(html));
    }

    /** Port-level US-07.4 contract: the connector's shared check. */
    @Override
    public void verifySyntax(String html) {
        checkSyntax(html);
    }

    /**
     * US-07.4: event handlers are dynamic code — any {@code on*}
     * attribute (onclick, onload, ...) is removed wherever it survived
     * the cleanse. Runs over the full document so handlers on the html
     * element are caught too.
     */
    private void removeEventHandlers(Document doc) {
        for (Element el : doc.select("*")) {
            for (org.jsoup.nodes.Attribute attr
                    : new ArrayList<>(el.attributes().asList())) {
                String key = attr.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.startsWith("on") && key.length() > 2) {
                    el.removeAttr(attr.getKey());
                }
            }
        }
    }

    /**
     * The US-07.4 syntax + exclusion check, exposed for the callers
     * that re-verify a stored artifact: the HTML5 must RE-PARSE
     * without parser errors, and must contain none of the forbidden
     * non-data elements (scripts, styles, media, popups, embedded
     * objects — the dynamic-code family). jsoup's HTML5 parser recovers
     * from any malformed input, so a re-parse NEVER throws; its error
     * list is the failed-syntax signal. A parse that yields null (a
     * defensive case: the parser always returns a document) fails too.
     *
     * @throws SimplifySyntaxException on any parse error or forbidden
     *         element — an error of the task run
     */
    public static void checkSyntax(String html) {
        org.jsoup.parser.Parser parser =
                org.jsoup.parser.Parser.htmlParser().setTrackErrors(10);
        Document reparsed;
        try {
            reparsed = parser.parseInput(html == null ? "" : html, "");
        } catch (RuntimeException e) {
            throw new SimplifySyntaxException(
                    "SIMPLIFY output does not parse as HTML5: " + e.getMessage());
        }
        if (reparsed == null) {
            throw new SimplifySyntaxException(
                    "SIMPLIFY output does not parse as HTML5 (no document returned)");
        }
        int errors = parser.getErrors().size();
        if (errors > 0) {
            throw new SimplifySyntaxException(
                    "SIMPLIFY output is not valid HTML5: " + errors
                            + " parse error(s), first: " + parser.getErrors().get(0));
        }
        for (Element forbidden : reparsed.select("*")) {
            if (FORBIDDEN_TAGS.contains(
                    forbidden.tagName().toLowerCase(java.util.Locale.ROOT))) {
                throw new SimplifySyntaxException(
                        "SIMPLIFY output contains a non-data element: <"
                                + forbidden.tagName() + ">");
            }
        }
    }

    /** US-07.4: the simplification produced non-compliant output. */
    public static class SimplifySyntaxException extends RuntimeException {
        public SimplifySyntaxException(String message) {
            super(message);
        }
    }

    /**
     * True when a nav element is a pagination control (generic HTML5
     * semantics: pagination label/class, rel=next or page-number links).
     */
    private boolean isPaginationNav(Element nav) {
        String marker = (nav.attr("aria-label") + " " + nav.className())
                .toLowerCase();
        return marker.contains("pagination") || marker.contains("pager")
                || !nav.select("[rel=next], a[href*=page=]").isEmpty();
    }

    /**
     * Filter widgets often render every option of a native
     * {@code <select>} TWICE — the native option list plus a generated
     * checkbox/label clone group (multi-select widgets do this; the
     * cloned group of a large facet form alone can outweigh the whole
     * listing and push the real data past the output cap). Both carry
     * the same values; the checkbox clone is pure duplication for the
     * analysis. Drop the checkbox entries whose text mirrors an option
     * of the select of the same widget group. Generic widget dedup —
     * the select (the smaller, canonical representation) is kept with
     * all its values.
     */
    private void dropDuplicatedWidgetCheckboxEntries(Document doc) {
        for (Element select : doc.select("form select")) {
            Set<String> optionTexts = new HashSet<>();
            for (Element option : select.select("option")) {
                String text = normalizedText(option.text());
                if (!text.isEmpty()) {
                    optionTexts.add(text);
                }
            }
            if (optionTexts.isEmpty()) {
                continue;
            }
            // Scope: the widget group that holds this select AND its
            // checkbox clones (their common container).
            Element scope = select.parent();
            while (scope != null && !scope.tagName().equals("form")
                    && scope.select("input[type=checkbox]").isEmpty()) {
                scope = scope.parent();
            }
            if (scope == null) {
                continue;
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Element el : scope.select("*")) {
                    if (el.select("input[type=checkbox]").size() != 1
                            || !el.select("select, article, form").isEmpty()) {
                        continue;
                    }
                    if (optionTexts.contains(normalizedText(el.text()))) {
                        el.remove();
                        changed = true;
                        break; // DOM changed — restart the scan
                    }
                }
            }
        }
    }

    private String normalizedText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase();
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
