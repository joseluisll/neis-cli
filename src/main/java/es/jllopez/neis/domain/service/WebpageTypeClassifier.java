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
        boolean html = contentType == null
                || contentType.isBlank()
                || contentType.toLowerCase(Locale.ROOT).contains("html");
        if (!html) {
            return "other";
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
}
