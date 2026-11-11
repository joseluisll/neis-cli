package es.jllopez.neis.application;

/**
 * The catalog of AI prompts for the ANALYZE step, one per task at hand
 * (owner decision 2026-11-11, MVP issue #90): classification of the
 * page content, and identification of the news / events list selector.
 *
 * <p>Each prompt carries a strict output contract so the caller can
 * parse the answer leniently. The HTML excerpt fed to the model is a
 * head+tail window of the simplified page: repeating item structures
 * usually sit after the page chrome (toolbars, filters) or near the
 * end of the main content, and a head-only window misses them.</p>
 */
final class AiPromptCatalog {

    private static final int HEAD_BUDGET = 9_000;
    private static final int TAIL_BUDGET = 9_000;

    private AiPromptCatalog() {
    }

    /** Task: classify the page content (US-13.2). Answer: one word. */
    static String classificationPrompt(String simplifiedHtml, String expectedKindHint) {
        return """
                You are classifying the content of a web page that may list news items \
                or events. Classify the page as exactly one of: news, event, other.
                The page is expected to be a %s listing; verify against the actual \
                content and answer with the single word (news, event or other) and \
                nothing else.
                --- SIMPLIFIED HTML EXCERPT (head and tail) ---
                %s""".formatted(expectedKindHint, excerpt(simplifiedHtml));
    }

    /** Task: identify the news list selector (US-13.3). */
    static String newsSelectorPrompt(String simplifiedHtml) {
        return selectorPrompt(simplifiedHtml, "news");
    }

    /** Task: identify the events list selector (US-13.4). */
    static String eventsSelectorPrompt(String simplifiedHtml) {
        return selectorPrompt(simplifiedHtml, "events");
    }

    private static String selectorPrompt(String simplifiedHtml, String kind) {
        return """
                You are analyzing the simplified HTML5 of a web page that lists %s items. \
                Identify the CSS selector that matches the REPEATING list items of the %s \
                listing (the container element of one single item, repeated for each entry — \
                not the whole list, not the pagination).
                The selector must be valid CSS and match against the given HTML.
                Answer ONLY with a JSON object:
                {"selector":"the css selector","reasoning":"short explanation"}
                If no repeating %s item structure is present, answer:
                {"selector":null,"reasoning":"why none was found"}
                --- SIMPLIFIED HTML EXCERPT (head and tail) ---
                %s""".formatted(kind, kind, kind, excerpt(simplifiedHtml));
    }

    /**
     * Head+tail window: repeating item containers usually sit after the
     * page chrome or near the end of the main content.
     */
    private static String excerpt(String html) {
        if (html.length() <= HEAD_BUDGET + TAIL_BUDGET) {
            return html;
        }
        return html.substring(0, HEAD_BUDGET)
                + "\n\n[... middle of the document omitted ...]\n\n"
                + html.substring(html.length() - TAIL_BUDGET);
    }
}
