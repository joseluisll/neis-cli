package es.jllopez.neis.application;

/**
 * The catalog of AI prompts for the analysis-side blocks, one per task
 * at hand (owner decision 2026-11-11, MVP issue #90; capabilities and
 * filter extraction added for US-17): classification of the page
 * content, capability detection (pagination, filtering), identification
 * of the news / events list selector, and extraction of the filter
 * definitions. Every block reuses the same AI service with its own
 * task-specific prompt.
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
    /** Form-focused window cap for the filters prompt (facet forms are wide). */
    private static final int FORM_BUDGET = 45_000;

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

    /**
     * Task: detect the listing CAPABILITIES of the page (US-17): whether
     * the page itself is paginated and whether it offers filter controls.
     * The AI carries the detection — the code never encodes site-specific
     * knowledge. When paginated, the AI also derives the page URL pattern
     * (page number marked as {n}) so the pagination-iteration block can
     * address the following pages.
     */
    static String capabilityPrompt(String simplifiedHtml, String expectedKindHint) {
        return """
                You are analyzing the simplified HTML5 of a web page that may list %s items.
                Detect which CAPABILITIES the page itself offers for that listing:
                1. pagination: the listing is paginated — pager links, page-number links \
                or next/previous controls are present. If paginated, also give \
                "pageUrlPattern": the URL template that retrieves page number N, with the \
                page number marked as {n}, derived from the pager links (e.g. \
                "https://example.org/list?page={n}"). If not paginated, pageUrlPattern is null.
                2. filtering: the page offers FILTER controls to narrow the listing — \
                filter forms, facet selectors, dropdowns of filter values, checkbox groups, \
                date-range pickers or free-text filter inputs.
                Answer ONLY with a JSON object:
                {"pagination":{"detected":true,"pageUrlPattern":"https://example.org/list?page={n}","reasoning":"..."},
                 "filtering":{"detected":true,"reasoning":"..."}}
                --- SIMPLIFIED HTML EXCERPT (filter-form and pager focused) ---
                %s""".formatted(expectedKindHint, formFocusedExcerpt(simplifiedHtml));
    }

    /**
     * Task: extract the FILTER definitions of a listing page flagged with
     * the filtering capability (US-17, FILTER block). The AI identifies
     * every filter field and the values it offers. The excerpt is
     * form-focused: the filter form usually sits in the middle of the
     * page (between the header chrome and the list), where a head+tail
     * window would miss it.
     */
    static String filtersPrompt(String simplifiedHtml) {
        return """
                You are analyzing the simplified HTML5 of a web page listing that offers \
                FILTER controls. Extract every filter field available to narrow the listing \
                (dropdown selects, facet groups, checkbox groups, date ranges, free-text \
                inputs), with the values the filter offers. For free-text inputs the values \
                list is empty.
                Answer ONLY with a JSON object:
                {"filters":[{"name":"human readable field name","values":["value1","value2"]}],
                 "reasoning":"short explanation"}
                If no filter controls are present, answer:
                {"filters":[],"reasoning":"why none were found"}
                --- SIMPLIFIED HTML EXCERPT (filter-form focused) ---
                %s""".formatted(formFocusedExcerpt(simplifiedHtml));
    }

    /**
     * Head+tail window, but always including the regions the analysis
     * blocks need: the filter-form region (first {@code <form>} to last
     * {@code </form>}) and the navigation region (first {@code <nav>} to
     * last {@code </nav>}, where pagination controls live). Both carry
     * the data the capability detection reads; a plain head+tail window
     * misses them in the middle of the page.
     */
    private static String formFocusedExcerpt(String html) {
        int firstForm = html.indexOf("<form");
        int lastFormEnd = html.lastIndexOf("</form>");
        int firstNav = html.indexOf("<nav");
        int lastNavEnd = html.lastIndexOf("</nav>");
        int start = -1;
        int end = -1;
        if (firstForm >= 0 && lastFormEnd > firstForm) {
            start = firstForm;
            end = lastFormEnd + "</form>".length();
        }
        if (firstNav >= 0 && lastNavEnd > firstNav) {
            start = start < 0 ? firstNav : Math.min(start, firstNav);
            end = Math.max(end, lastNavEnd + "</nav>".length());
        }
        if (start < 0) {
            return excerpt(html);
        }
        String region = html.substring(start, Math.min(html.length(), end));
        if (region.length() > FORM_BUDGET) {
            region = region.substring(0, FORM_BUDGET)
                    + "\n[... rest of the region omitted ...]";
        }
        return "[page head]\n" + html.substring(0, Math.min(html.length(), 2_000))
                + "\n\n[filter form and navigation region]\n" + region
                + "\n\n[page tail]\n" + html.substring(Math.max(0, html.length() - 2_000));
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
                --- SIMPLIFIED HTML EXCERPT (list-item focused) ---
                %s""".formatted(kind, kind, kind, listFocusedExcerpt(simplifiedHtml));
    }

    /**
     * Head+tail window, but when the page clearly carries a list of
     * repeating {@code <article>} items, the window centers on the list
     * region (first to last article) — listing items usually sit in the
     * middle of the page, where a plain head+tail window would miss them.
     */
    private static String listFocusedExcerpt(String html) {
        int firstArticle = html.indexOf("<article");
        int lastArticleEnd = html.lastIndexOf("</article>");
        if (firstArticle < 0 || lastArticleEnd <= firstArticle) {
            return excerpt(html);
        }
        String listRegion = html.substring(firstArticle,
                Math.min(html.length(), lastArticleEnd + "</article>".length()));
        if (listRegion.length() > FORM_BUDGET) {
            listRegion = listRegion.substring(0, FORM_BUDGET)
                    + "\n[... rest of the list region omitted ...]";
        }
        return "[page head]\n" + html.substring(0, Math.min(html.length(), 2_000))
                + "\n\n[list region]\n" + listRegion
                + "\n\n[page tail]\n" + html.substring(Math.max(0, html.length() - 2_000));
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
