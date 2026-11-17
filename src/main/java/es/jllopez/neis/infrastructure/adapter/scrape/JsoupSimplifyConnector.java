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
// Not the wired SimplifyPort bean: the router (US-07.5) is. This
// connector is the jsoup Safelist extractor the router delegates to
// (and the fallback for non-article structures) — two beans of the
// port would break the single-candidate injection into PipelineService.
@org.springframework.beans.factory.annotation.Qualifier(
        "jsoupSimplifyConnector")
public class JsoupSimplifyConnector implements SimplifyPort {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JsoupSimplifyConnector.class);

    /** Output cap, US-07.3. */
    static final int MAX_OUTPUT_BYTES = 100 * 1024;

    /** US-07.6 collaborators (same infrastructure layer, optional at
     * construction for the hand-rolled test graphs). */
    private final JsoupDomAnalyzer domAnalyzer;
    private final PrunedTreeHtmlRenderer treeRenderer;

    /**
     * US-07.4: elements whose presence makes the output non-compliant —
     * dynamic code (scripts, embedded objects), media, popup/iframe and
     * dialog/template content. ONE Set drives BOTH the cleanse and the
     * check (US-07.4 review finding B1: two lists drift — a forbidden
     * element that is never removed fails the whole run instead of being
     * simplified). An exact-name Set, never a substring test (a
     * {@code p} selector must not match {@code template}).
     */
    private static final java.util.Set<String> FORBIDDEN_TAGS =
            java.util.Set.of("script", "style", "link", "meta", "noscript",
                    "iframe", "frame", "frameset", "object", "embed", "applet",
                    "svg", "img", "picture", "source", "video", "audio",
                    "track", "canvas", "map", "area", "dialog", "template",
                    "noembed", "param");

    /**
     * The non-data element strip list, DERIVED from FORBIDDEN_TAGS (one
     * source of truth — the selector is the Set joined, so a tag added to
     * the Set is stripped AND checked; US-07.4 review finding B1). Order:
     * alphabetical by construction of the join — deterministic.
     */
    private static final String STRIPPED_TAGS =
            String.join(", ", FORBIDDEN_TAGS.stream().sorted()
                    .toArray(String[]::new));

    /**
     * Structural/layout-only containers (US-07.1), kept as a separate
     * selector: nav is handled separately below (pagination navs are
     * kept for the ANALYZE capability detection, US-17).
     */
    private static final String STRIPPED_STRUCTURE_TAGS = "header, footer, aside";

    /**
     * Dialog/template are forbidden (popups/dynamic content) but may
     * WRAP data — an open {@code <dialog>} often holds the newsletter or
     * help popup AND sometimes real content. They are UNWRAPPED (children
     * promoted, element dropped), not removed, so data inside survives;
     * everything else on the forbidden list is removed outright. Frames
     * and frame content are structural relics (no listing data).
     */
    private static final java.util.Set<String> UNWRAP_TAGS =
            java.util.Set.of("dialog", "template");

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
            "skiplink", "a11y",
            // US-07.4 review finding B2: advertisements and popups are
            // excluded by the requirement, not only the standard ad
            // elements the obstruction clearing removes before this step
            // (a SIMPLIFY input may come from other paths).
            "popup", "advert", "adsbygoogle"};

    /** Test/hand-rolled constructor: no tiered rendering collaborators. */
    public JsoupSimplifyConnector() {
        this.domAnalyzer = null;
        this.treeRenderer = null;
    }

    /** Spring constructor: the tiered rendering collaborators. */
    public JsoupSimplifyConnector(JsoupDomAnalyzer domAnalyzer,
                                  PrunedTreeHtmlRenderer treeRenderer) {
        this.domAnalyzer = domAnalyzer;
        this.treeRenderer = treeRenderer;
    }

    @Override
    public SimplifiedContent simplify(String rawHtml) {
        return simplify(rawHtml, es.jllopez.neis.domain.model.SimplifyVariant.FULL);
    }

    /**
     * US-07.6: the tiered variant renders FROM THE PRUNED ELEMENT TREE
     * when the collaborators are present (Spring wiring); without them
     * (hand-rolled tests, and the FULL tier's backward-compatible
     * default) the cleanse result is returned as before — the FULL
     * tier IS the cleanse output, and the renderer is invoked for
     * economy/lean.
     */
    @Override
    public SimplifiedContent simplify(String rawHtml, String variant) {
        es.jllopez.neis.domain.model.SimplifyVariant checked =
                new es.jllopez.neis.domain.model.SimplifyVariant(variant);
        if (domAnalyzer != null && treeRenderer != null) {
            try {
                java.util.Map<String, String> placeholders = new java.util.LinkedHashMap<>();
                Document pruned = domAnalyzer.prune(rawHtml, placeholders);
                SimplifiedContent rendered = treeRenderer.render(pruned, checked.id());
                return rendered;
            } catch (RuntimeException e) {
                // The pruned-tree rendering is a US-07.6 enhancement; a
                // failure falls back to the cleanse output (the data
                // preservation rule outranks the tier). Never silent:
                log.info("SIMPLIFY tiered rendering failed ({}) — "
                        + "returning the full cleanse output", e.getMessage());
            }
        }
        if (!es.jllopez.neis.domain.model.SimplifyVariant.FULL.equals(checked.id())) {
            log.info("SIMPLIFY tiered rendering unavailable (no renderer "
                    + "wired) — {} tier degrades to the full cleanse output",
                    checked.id());
        }
        return cleanse(rawHtml);
    }

    /** The US-07.1/07.2 cleanse (the FULL tier's rendering). */
    private SimplifiedContent cleanse(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);
        doc.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);

        // US-07.4: unwrap the forbidden-but-maybe-data containers
        // FIRST (dialog/template — their children survive, the element
        // does not), then remove every other forbidden non-data element
        // (derived from the same Set the compliance check scans for),
        // then the structural chrome (US-07.1).
        unwrapForbiddenContainers(doc);
        doc.select(STRIPPED_TAGS).remove();
        doc.select(STRIPPED_STRUCTURE_TAGS).remove();
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
        neutraliseScriptUrls(doc);
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
     * US-07.4 (review finding B1): dialog/template are forbidden but may
     * wrap data — unwrap them (promote children, drop the element)
     * before the removal sweep so the data inside survives.
     *
     * <p>US-07.4 (review round-2 finding B3): a dialog/template that
     * declares itself a POPUP (class/aria-label/id matching the
     * popup/modal/ad fragments) is removed WITH its content, not
     * unwrapped — unwrapping a popup promotes the popup text into the
     * page, which the requirement excludes.</p>
     */
    private void unwrapForbiddenContainers(Document doc) {
        for (String tag : UNWRAP_TAGS.stream().sorted().toArray(String[]::new)) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Element el : doc.select(tag)) {
                    if (isDeclaredPopup(el)) {
                        el.remove();
                    } else {
                        el.unwrap();
                    }
                    changed = true;
                    break; // DOM changed — restart the scan
                }
            }
        }
    }

    /** True when the element's class/id/aria-label declares a popup,
     * modal or advertisement container (the generic fragments). */
    private boolean isDeclaredPopup(Element el) {
        String marker = (el.className() + " " + el.id() + " "
                + el.attr("aria-label")).toLowerCase(java.util.Locale.ROOT);
        for (String fragment : new String[]{"popup", "modal", "advert",
                "adsbygoogle", "overlay"}) {
            if (marker.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * US-07.4 (review finding B2): a {@code javascript:} URL IS dynamic
     * code. The attribute is dropped and the ELEMENT KEPT (an {@code <a>}
     * without href is still an action element; its text is data) —
     * removal maximal only where no data is lost. Covered by the syntax
     * check too (below), so a future cleanse regression trips the
     * tripwire instead of passing silently.
     */
    private void neutraliseScriptUrls(Document doc) {
        for (Element el : doc.select("*")) {
            for (org.jsoup.nodes.Attribute attr
                    : new ArrayList<>(el.attributes().asList())) {
                String value = attr.getValue();
                if (value != null && value.trim().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("javascript:")) {
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
            // US-07.4 (review finding B2): javascript: URLs are dynamic
            // code — the check refuses them wherever they survived.
            for (org.jsoup.nodes.Attribute attr
                    : new ArrayList<>(forbidden.attributes().asList())) {
                String value = attr.getValue();
                if (value != null && value.trim()
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("javascript:")) {
                    throw new SimplifySyntaxException(
                            "SIMPLIFY output contains dynamic code: attribute '"
                                    + attr.getKey() + "' with a javascript: URL on <"
                                    + forbidden.tagName() + ">");
                }
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
