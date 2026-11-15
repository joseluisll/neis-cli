package es.jllopez.neis.domain.model;

/**
 * One webpage type of the NEIS taxonomy (US-06.5, issue #71): an
 * extensible, configuration-driven classification of the webpages
 * NEIS processes — deliberately NOT a closed enum, so a new type can
 * be added through configuration without touching the domain layer
 * (the catalog port supplies the known types).
 *
 * <p>Every type maps to a retrieval strategy — how its pages are
 * fetched, behind the same fetching port (US-01.4, US-06.1):</p>
 *
 * <ul>
 *   <li>{@link #STRATEGY_HTTP} — plain HTTP fetch (server-rendered
 *       content; Apache HttpClient / java.net.http).</li>
 *   <li>{@link #STRATEGY_RENDERED} — client-side rendering in a real
 *       browser (Playwright for Java) before extraction.</li>
 * </ul>
 *
 * <p>Built-in types (the configuration can extend the taxonomy with
 * further ids mapped to either strategy):</p>
 *
 * <ul>
 *   <li>{@code static} — server-rendered HTML (Confluence pages,
 *       Drupal listings).</li>
 *   <li>{@code spa-angular}, {@code spa-react}, {@code spa-vue},
 *       {@code spa} — client-rendered applications by framework
 *       family ({@code spa} is the family-agnostic fallback).</li>
 *   <li>{@code hybrid} — server-rendered base with client-rendered
 *       fragments/widgets.</li>
 *   <li>{@code other} — anything else (API-only, non-HTML sources).</li>
 * </ul>
 */
public record WebpageType(String id, String strategy) {

    /** Retrieval strategy: plain HTTP fetch of the server response. */
    public static final String STRATEGY_HTTP = "http";

    /** Retrieval strategy: render the page in a browser (Playwright). */
    public static final String STRATEGY_RENDERED = "rendered";

    public WebpageType {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Webpage type id is required");
        }
        if (!STRATEGY_HTTP.equals(strategy) && !STRATEGY_RENDERED.equals(strategy)) {
            throw new IllegalArgumentException("Unknown retrieval strategy '"
                    + strategy + "' for webpage type '" + id
                    + "' (expected '" + STRATEGY_HTTP + "' or '"
                    + STRATEGY_RENDERED + "')");
        }
    }
}
