package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.ElementCatalog;
import es.jllopez.neis.domain.model.PrunedElement;
import es.jllopez.neis.domain.port.DomAnalysisPort;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Connector (driven adapter) implementing the DOM-analysis port with
 * jsoup (US-06.9, issue #88): a re-implementation IN JAVA of the
 * useful concepts of Skyvern's scraper (Skyvern-AI/skyvern, Python,
 * AGPL-3.0 — concepts only, NO code copied; the license difference is
 * respected by this clean-room Java adaptation):
 *
 * <ul>
 *   <li><b>Element tree with incremental ids</b>: every relevant
 *   element (visible-or-interactable, see below) gets {@code e<N>} in
 *   document order plus a deterministic CSS path — the id-to-CSS map
 *   selectors, AI answers and users reference elements by.</li>
 *   <li><b>Visibility and relevance filtering</b>: zero-area
 *   ({@code [aria-hidden=true]}, {@code hidden} attribute, {@code
 *   display:none}/{@code visibility:hidden} inline style) and
 *   irrelevant elements ({@code script}, {@code style}, {@code meta},
 *   {@code link}, {@code noscript}, {@code template}, {@code head})
 *   are marked dropped; a dropped NON-interactable element is pruned
 *   from the tree entirely, a dropped INTERACTABLE one keeps its tag
 *   but loses its attributes (Skyvern's isDropped semantics).</li>
 *   <li><b>Interactability detection</b>: {@code a[href]}, {@code
 *   button}, {@code input}, {@code select}, {@code textarea},
 *   {@code [onclick]}, {@code [role=button]} and form elements are
 *   flagged; {@code disabled}/{@code aria-disabled} is carried in the
 *   whitelisted attributes. (Geometry is available only in rendered
 *   mode; the static/jsoup analysis carries the DOM-level signal.)</li>
 *   <li><b>Attribute whitelist and trimming</b>: only attributes of
 *   interest survive (PrunedElement.WHITELISTED_ATTRIBUTES), values
 *   are trimmed and capped at 200 chars, URLs over 2048 chars are
 *   hashed into {@code hash:<12 hex>} placeholders (mapped back in
 *   the catalog, so nothing is lost — the tree stays small).</li>
 *   <li><b>Change digest</b>: SHA-256 over the canonical tree
 *   rendering (id|tag|css|role|interactable|dropped|attrs per line) —
 *   the same page content always yields the same digest, so unchanged
 *   pages are detected between runs (stored with the capture,
 *   US-11.4).</li>
 * </ul>
 */
@Component
public class JsoupDomAnalyzer implements DomAnalysisPort {

    /** Elements never relevant to the analysis (Skyvern prunes these). */
    private static final java.util.Set<String> IRRELEVANT_TAGS =
            java.util.Set.of("script", "style", "meta", "link", "noscript",
                    "template", "head", "title");

    /** Implicit landmark/interactive roles of plain HTML tags. */
    private static final Map<String, String> IMPLICIT_ROLES = Map.ofEntries(
            Map.entry("a", "link"), Map.entry("button", "button"),
            Map.entry("select", "listbox"), Map.entry("textarea", "textbox"),
            Map.entry("main", "main"), Map.entry("nav", "navigation"),
            Map.entry("header", "banner"), Map.entry("footer", "contentinfo"),
            Map.entry("form", "form"), Map.entry("article", "article"),
            Map.entry("aside", "complementary"),
            Map.entry("h1", "heading"), Map.entry("h2", "heading"),
            Map.entry("h3", "heading"), Map.entry("h4", "heading"),
            Map.entry("h5", "heading"), Map.entry("h6", "heading"),
            Map.entry("ul", "list"), Map.entry("ol", "list"),
            Map.entry("li", "listitem"), Map.entry("img", "img"),
            Map.entry("input", "textbox"));

    /**
     * The US-07.6 prune AS A DOM TRANSFORM: a copy of the input
     * document with the same semantics the catalog walk applies —
     * irrelevant/hidden NON-interactable elements removed entirely,
     * dropped INTERACTABLE ones kept as bare tags (attributes gone),
     * and every surviving element reduced to its whitelisted
     * attributes (long URLs hashed into the returned placeholder map).
     * The SIMPLIFY tiered rendering renders FROM this pruned tree.
     */
    public Document prune(String html, Map<String, String> placeholders) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        pruneNode(doc.body(), placeholders);
        return doc;
    }

    /** The recursive prune (the walk's DOM-transform counterpart). */
    private void pruneNode(Element el, Map<String, String> placeholders) {
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        boolean irrelevant = IRRELEVANT_TAGS.contains(tag);
        boolean dropped = irrelevant || isHidden(el);
        boolean interactable = isInteractable(el);
        if (dropped && !interactable) {
            el.remove();
            return;
        }
        if (dropped) { // dropped interactable: bare tag
            el.attributes().asList().forEach(a -> el.removeAttr(a.getKey()));
        } else {
            Map<String, String> keep = whitelist(el, placeholders);
            for (org.jsoup.nodes.Attribute a
                    : new ArrayList<>(el.attributes().asList())) {
                el.removeAttr(a.getKey());
            }
            keep.forEach(el::attr);
        }
        for (Element child : new ArrayList<>(el.children())) {
            if (child.ownerDocument() != null) { // not removed above
                pruneNode(child, placeholders);
            }
        }
    }

    @Override
    public ElementCatalog analyze(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        Map<String, String> placeholders = new LinkedHashMap<>();
        List<PrunedElement> elements = new ArrayList<>();
        StringBuilder canonical = new StringBuilder();
        int[] counter = {0};

        // The walk starts at the body's CHILDREN: the body element
        // itself is the page container, not a catalogable element
        // (cataloging it would make every page carry a synthetic root).
        for (Element child : doc.body().children()) {
            walk(child, elements, canonical, placeholders, counter, "");
        }

        String digest = sha256(canonical.toString());
        return new ElementCatalog(elements, digest, placeholders);
    }

    /**
     * Depth-first walk producing the pruned tree: every element is
     * classified (dropped? interactable?) and either cataloged, pruned
     * entirely (dropped non-interactable) or cataloged as a bare tag
     * (dropped interactable). Children of cataloged elements are
     * visited either way, so the ids follow document order.
     */
    private void walk(Element el, List<PrunedElement> elements,
                      StringBuilder canonical, Map<String, String> placeholders,
                      int[] counter, String parentPath) {
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        String cssPath = cssPath(el, tag, parentPath);
        boolean irrelevant = IRRELEVANT_TAGS.contains(tag);
        boolean dropped = irrelevant || isHidden(el);
        boolean interactable = isInteractable(el);

        // Skyvern's isDropped semantics: a dropped non-interactable
        // element disappears from the tree; a dropped interactable one
        // keeps its tag but loses its attributes.
        if (!dropped || interactable) {
            counter[0]++;
            String id = "e" + counter[0];
            Map<String, String> attributes = dropped
                    ? Map.of()
                    : whitelist(el, placeholders);
            PrunedElement element = new PrunedElement(id, tag, cssPath,
                    roleOf(el), dropped ? "" : normalizedText(el),
                    attributes, interactable, dropped);
            elements.add(element);
            canonical.append(canonicalLine(element));
        }

        for (Element child : el.children()) {
            walk(child, elements, canonical, placeholders, counter, cssPath);
        }
    }

    /** The visibility/relevance filter (DOM-level signals). Style
     * matching tolerates whitespace variants and case around the
     * colon ({@code display :  none}, {@code DISPLAY:NONE}) — the
     * exact-substring test missed them (review non-blocking finding);
     * stylesheet-driven hiding stays out of scope by design. */
    private boolean isHidden(Element el) {
        if (el.hasAttr("hidden") || "true".equalsIgnoreCase(el.attr("aria-hidden"))) {
            return true;
        }
        String style = el.attr("style").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        return style.contains("display:none")
                || style.contains("visibility:hidden");
    }

    /** The interactability flag (US-06.9): actionable elements. */
    private boolean isInteractable(Element el) {
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        return switch (tag) {
            case "a", "button", "input", "select", "textarea", "label" -> true;
            default -> el.hasAttr("onclick")
                    || "button".equalsIgnoreCase(el.attr("role"));
        };
    }

    /** Whitelisted attributes of interest, trimmed and placeholdered.
     * A boolean attribute present WITHOUT a value (jsoup returns "")
     * is recorded as "true" — dropping it would lose the signal
     * (disabled/required/checked say something by their presence). */
    private Map<String, String> whitelist(Element el, Map<String, String> placeholders) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String attr : PrunedElement.WHITELISTED_ATTRIBUTES) {
            if (!el.hasAttr(attr)) {
                continue;
            }
            String value = el.attr(attr);
            if (value.isEmpty()) {
                value = "true";
            }
            value = trimValue(attr, value, placeholders);
            out.put(attr, value);
        }
        return out;
    }

    /** URL-typed attributes: their values are URLs even when RELATIVE,
     * so the generic truncation cap must never corrupt them (#144). */
    private static final java.util.Set<String> URL_ATTRIBUTES =
            java.util.Set.of("href", "action", "src");

    /** trim_element: cap the value; hash an oversized URL (#144: a URL
     * value is exempt from the 200-char cap at ANY length — a substring
     * of a URL is a corrupted URL, and abs-resolution then stores junk;
     * up to MAX_URL_LENGTH a URL is kept INTACT, beyond it hashed). */
    private String trimValue(String attr, String value, Map<String, String> placeholders) {
        boolean isUrl = URL_ATTRIBUTES.contains(attr)
                || value.startsWith("http://") || value.startsWith("https://");
        if (isUrl) {
            if (value.length() > PrunedElement.MAX_URL_LENGTH) {
                String hash = sha256(value).substring(0, 12);
                String placeholder = "hash:" + hash;
                placeholders.putIfAbsent(placeholder, value);
                return placeholder;
            }
            return value;
        }
        if (value.length() > PrunedElement.MAX_VALUE_LENGTH) {
            return value.substring(0, PrunedElement.MAX_VALUE_LENGTH);
        }
        return value;
    }

    /** The role: the explicit attribute, else the implicit tag role.
     * An {@code input} maps by its {@code type}: a checkbox/radio is a
     * {@code checkbox}/{@code radio}, a submit/button/reset a
     * {@code button}, a range a {@code slider} — the flat
     * input→textbox mapping (review non-blocking finding) mislabeled
     * every non-text control in the catalog the AI consumes. */
    private String roleOf(Element el) {
        String explicit = el.attr("role");
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        if (tag.equals("input")) {
            String type = el.attr("type").toLowerCase(Locale.ROOT);
            return switch (type) {
                case "checkbox" -> "checkbox";
                case "radio" -> "radio";
                case "submit", "button", "reset", "image" -> "button";
                case "range" -> "slider";
                case "search" -> "searchbox";
                default -> "textbox"; // text, email, password, tel, url, date...
            };
        }
        return IMPLICIT_ROLES.getOrDefault(tag, "");
    }

    private String normalizedText(Element el) {
        String text = el.ownText().trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            // Own text of a container may be empty; the immediate
            // children's combined text is still informative but the
            // catalog keeps entries SMALL: cap at 200 chars.
            text = el.text().trim().replaceAll("\\s+", " ");
        }
        return text.length() > PrunedElement.MAX_VALUE_LENGTH
                ? text.substring(0, PrunedElement.MAX_VALUE_LENGTH) : text;
    }

    /**
     * A deterministic, jsoup-supported CSS path for the element: the
     * chain of tags from the body down, with an id anchor when the
     * element (or an ancestor) carries one, plus nth-of-type positions
     * where a tag repeats among its siblings — reparse + select()
     * with this path finds exactly this element (the id-to-CSS map).
     */
    private String cssPath(Element el, String tag, String parentPath) {
        if (el.id() != null && !el.id().isEmpty()) {
            return "#" + el.id();
        }
        StringBuilder step = new StringBuilder(tag);
        Element parent = el.parent();
        if (parent != null && !parent.tagName().equalsIgnoreCase("html")
                && !parent.tagName().equalsIgnoreCase("#root")) {
            long index = 1;
            for (Element sibling : parent.children()) {
                if (sibling.equals(el)) {
                    break;
                }
                if (sibling.tagName().equalsIgnoreCase(tag)) {
                    index++;
                }
            }
            long sameTag = parent.children().stream()
                    .filter(s -> s.tagName().equalsIgnoreCase(tag)).count();
            if (sameTag > 1) {
                step.append(":nth-of-type(").append(index).append(")");
            }
        }
        // A body-level root step is scoped to body: without it, two
        // first-of-tag elements under DIFFERENT parents (body and a
        // nested container) both match "div:nth-of-type(1)" — the
        // id-to-CSS map contract is that every path resolves to
        // EXACTLY the element it was emitted for (review B1 finding).
        return parentPath.isEmpty() ? "body > " + step
                : parentPath + " > " + step;
    }

    /** One canonical line per element: the digest's input. The TEXT is
     * part of the digest — a content change must change the digest
     * (that is the point of the change detection). */
    private String canonicalLine(PrunedElement e) {
        StringBuilder line = new StringBuilder()
                .append(e.id()).append('|').append(e.tag()).append('|')
                .append(e.cssPath()).append('|').append(e.role()).append('|')
                .append(e.text()).append('|')
                .append(e.interactable()).append('|').append(e.dropped());
        e.attributes().forEach((k, v) ->
                line.append('|').append(k).append('=').append(v));
        return line.append('\n').toString();
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The checkbox/radio facet groups of the page (US-06.19, issue
     * #150), by DOM traversal: every labeled checkbox (or radio) joins
     * the group its nearest preceding container heading names — the
     * walk climbs from the control and, at each level, scans the
     * preceding siblings for the closest one whose text is short and
     * that carries NO checkbox itself (a row of the same panel is not
     * its group's heading; the panel's header is). Values are the
     * controls' labels ({@code label for}; a control with no label is
     * skipped — it names nothing). Radios group by their {@code name}
     * attribute first (the standard HTML form semantics).
     */
    @Override
    public java.util.List<es.jllopez.neis.domain.model.FilterDefinition>
            checkboxFacetGroups(String html) {
        java.util.List<es.jllopez.neis.domain.model.FilterDefinition> filters =
                new java.util.ArrayList<>();
        if (html == null || html.isBlank()) {
            return filters;
        }
        Document doc = Jsoup.parse(html);
        // label-for map first: one pass, exact matches only
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (Element label : doc.select("label[for]")) {
            String text = label.text().trim();
            if (!text.isEmpty() && text.length() <= 60) {
                labels.putIfAbsent(label.attr("for"), text);
            }
        }
        java.util.Map<String, java.util.List<String>> groupValues =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, String> groupNames = new java.util.HashMap<>();
        for (Element input : doc.select("input[type=checkbox], input[type=radio]")) {
            String type = input.attr("type").toLowerCase(java.util.Locale.ROOT);
            String id = input.id();
            String label = labels.get(id);
            if (label == null) {
                String aria = input.attr("aria-label");
                if (!aria.isBlank() && aria.length() <= 60
                        && !"Input Unchecked".equalsIgnoreCase(aria)) {
                    label = aria.trim();
                }
            }
            if (label == null) {
                continue;
            }
            String key;
            String name;
            String heading = sectionHeading(input);
            if ("radio".equals(type) && !input.attr("name").isBlank()) {
                String nameAttr = input.attr("name");
                key = "name:" + nameAttr.toLowerCase(java.util.Locale.ROOT);
                name = groupNames.computeIfAbsent(key,
                        k -> heading != null ? heading : capitalize(nameAttr));
            } else {
                key = "section:" + heading;
                name = groupNames.computeIfAbsent(key,
                        k -> heading != null ? heading : "(checkbox group)");
            }
            groupValues.computeIfAbsent(key, k -> new java.util.ArrayList<>());
            if (!groupValues.get(key).contains(label)) {
                groupValues.get(key).add(label);
            }
        }
        for (var entry : groupValues.entrySet()) {
            filters.add(new es.jllopez.neis.domain.model.FilterDefinition(
                    groupNames.get(entry.getKey()), entry.getValue()));
        }
        return filters;
    }

    /**
     * The nearest preceding container heading of a control: climbing
     * the ancestors, the closest preceding sibling whose text is short
     * and that carries no checkbox itself (a sibling holding checkboxes
     * is a ROW of the same facet panel, never its group's heading).
     */
    private static String sectionHeading(Element input) {
        Element node = input;
        while (node != null) {
            for (Element s = node.previousElementSibling(); s != null;
                    s = s.previousElementSibling()) {
                if (!s.select("input[type=checkbox], input[type=radio]").isEmpty()) {
                    continue;
                }
                String text = s.text().trim();
                if (text.length() >= 2 && text.length() <= 40) {
                    return text;
                }
            }
            node = node.parent();
        }
        return null;
    }

    private static String capitalize(String s) {
        return s == null || s.isBlank() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
