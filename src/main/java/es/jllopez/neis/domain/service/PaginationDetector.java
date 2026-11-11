package es.jllopez.neis.domain.service;

import es.jllopez.neis.domain.model.PaginationInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain service: detects whether a listing page is paginated
 * (requirement US-12.4, issue #67). Pure Java heuristics over the
 * simplified HTML — no stack dependencies, per the US-01.4 freeze rule.
 *
 * <p>MVP scope is detection only; iteration across pages is a later
 * requirement slice.</p>
 */
public class PaginationDetector {

    /** href/URL patterns typical of paginated listings (jsoup escapes & as &amp;). */
    private static final Pattern URL_PATTERNS = Pattern.compile(
            "href=\"[^\"]*[?&](?:amp;)?(page|pageNumber|p|start|offset|from|pagina)[/=][^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /** rel="next" link relation. */
    private static final Pattern REL_NEXT = Pattern.compile(
            "rel=\"next\"|rel='next'", Pattern.CASE_INSENSITIVE);

    /** Pager markup and next-page labels ("page" alone must NOT match). */
    private static final Pattern PAGER_MARKUP = Pattern.compile(
            "class=\"[^\"]*(pagination|paginator|pager|paged)[^\"]*\""
                    + "|>(?:next|next page|siguiente|&rsaquo;|&gt;|»)\s*</a>",
            Pattern.CASE_INSENSITIVE);

    /**
     * Detects pagination controls (next-page links, pager elements) and
     * URL patterns such as {@code ?page=N} in the given HTML.
     */
    public PaginationInfo detect(String html) {
        if (html == null || html.isBlank()) {
            return PaginationInfo.notPaginated();
        }
        Matcher urls = URL_PATTERNS.matcher(html);
        if (urls.find()) {
            return new PaginationInfo(true,
                    "Paginated URL pattern detected: " + urls.group());
        }
        Matcher rel = REL_NEXT.matcher(html);
        if (rel.find()) {
            return new PaginationInfo(true, "rel=next link relation detected");
        }
        Matcher pager = PAGER_MARKUP.matcher(html);
        if (pager.find()) {
            return new PaginationInfo(true,
                    "Pager markup detected: " + pager.group().trim());
        }
        return PaginationInfo.notPaginated();
    }
}
