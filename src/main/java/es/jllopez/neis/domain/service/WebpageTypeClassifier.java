package es.jllopez.neis.domain.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies a fetched page into the webpage-type taxonomy (US-06.5,
 * issue #71) from its content type and body sample. Deterministic
 * marker heuristics — never AI — so every classification is
 * reproducible and explainable; the US-13.6 preflight may layer its
 * richer estimation on top later.
 *
 * <p>Rules, in order:</p>
 * <ol>
 *   <li>a non-HTML content type classifies as {@code other} (API-only
 *       or non-HTML sources need no rendering);</li>
 *   <li>a framework marker in the body classifies as the matching
 *       {@code spa-<framework>} type ({@code spa} when only generic
 *       client-rendered hints are present);</li>
 *   <li>framework markers AND a {@code <noscript>} fallback classify
 *       as {@code hybrid} (server-rendered base + client widgets);</li>
 *   <li>an RSS/Atom feed document (feed content type, or the
 *       {@code <rss>}/{@code <feed>} root elements) classifies as
 *       {@code feed} (US-06.8);</li>
 *   <li>HTML without markers classifies as {@code static}.</li>
 * </ol>
 *
 * <p>An unparseable/empty body is unclassifiable: the caller receives
 * an empty result and keeps the previously stored type (a failed
 * probe never wipes a classification).</p>
 */
public final class WebpageTypeClassifier {

    /** Framework marker -> taxonomy id, checked in insertion order. */
    private static final Map<String, String> FRAMEWORK_MARKERS = buildMarkers();

    private static Map<String, String> buildMarkers() {
        Map<String, String> markers = new LinkedHashMap<>();
        // Angular: bootstrap attributes and emitted component attributes.
        markers.put("ng-version", "spa-angular");
        markers.put("ng-app", "spa-angular");
        markers.put("_nghost", "spa-angular");
        markers.put("ngcontent-", "spa-angular");
        markers.put("<app-root", "spa-angular");
        // React (incl. Next.js server payloads).
        markers.put("__next_data__", "spa-react");
        markers.put("data-reactroot", "spa-react");
        markers.put("__reactcontainer", "spa-react");
        // Vue (incl. Nuxt payloads).
        markers.put("data-v-app", "spa-vue");
        markers.put("__vue__", "spa-vue");
        markers.put("__nuxt", "spa-vue");
        // Generic client-rendered hints (no framework identified).
        markers.put("id=\"root\"", "spa");
        markers.put("id=\"app\"", "spa");
        return markers;
    }

    /**
     * The taxonomy id of the page, or empty when it cannot be
     * classified. {@code bodySample} is a lowercase-tolerant prefix of
     * the response body (a few hundred KB is ample — framework
     * bootstrap markers sit in the first payload bytes); the content
     * type is the final response's.
     */
    public String classify(String contentType, String bodySample) {
        if (bodySample == null || bodySample.isBlank()) {
            return null;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean html = type.isBlank() || type.contains("html");
        if (!html) {
            // US-06.8 (issue #86): a feed document is its own source
            // type — fetched and parsed, never rendered.
            if (type.contains("rss") || type.contains("atom")
                    || (type.contains("xml") && isFeedDocument(bodySample))) {
                return "feed";
            }
            return "other";
        }
        if (isFeedDocument(bodySample)) {
            // A feed served as text/xml (or mislabeled text/html): the
            // root element decides, before any HTML heuristics.
            return "feed";
        }
        String body = bodySample.toLowerCase(Locale.ROOT);
        String framework = null;
        for (Map.Entry<String, String> marker : FRAMEWORK_MARKERS.entrySet()) {
            if (body.contains(marker.getKey())) {
                framework = stronger(framework, marker.getValue());
            }
        }
        if (framework == null) {
            return "static";
        }
        // A framework marker plus a noscript fallback: server-rendered
        // base with client-rendered fragments — hybrid. (A plain SPA
        // shell has nothing meaningful inside <noscript>.)
        if (body.contains("<noscript>") && body.contains("</noscript>")
                && !framework.equals("spa")) {
            return "hybrid";
        }
        return framework;
    }

    /** The specific framework id wins over the generic {@code spa} hint. */
    private static String stronger(String current, String candidate) {
        if (current == null || current.equals("spa")) {
            return candidate;
        }
        return current;
    }

    /**
     * Whether the body sample opens like an RSS or Atom document: the
     * root element (after any XML declaration/comments) is
     * {@code <rss>} or {@code <feed>}. Checked on the raw sample —
     * case-insensitive on the element name, tolerant of leading
     * whitespace.
     */
    private boolean isFeedDocument(String bodySample) {
        String head = bodySample.trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<?xml")) {
            int end = head.indexOf("?>");
            head = end >= 0 ? head.substring(end + 2).trim() : head;
        }
        while (head.startsWith("<!--")) {
            int end = head.indexOf("-->");
            head = end >= 0 ? head.substring(end + 3).trim() : head;
        }
        return isRootElementNamed(head, "rss") || isRootElementNamed(head, "feed");
    }

    /**
     * Whether the trimmed head starts with a root element of exactly
     * the given name: the name must be followed by a delimiter
     * (whitespace, '&gt;' or '/'), so {@code <feedback>} is never a
     * match for {@code feed}.
     */
    private static boolean isRootElementNamed(String head, String name) {
        if (!head.startsWith("<" + name)) {
            return false;
        }
        // A truncated sample ending exactly at the element name has no
        // delimiter: not a recognizable document, never a crash.
        if (head.length() <= 1 + name.length()) {
            return false;
        }
        char next = head.charAt(1 + name.length());
        return next == '>' || next == '/' || Character.isWhitespace(next);
    }
}
