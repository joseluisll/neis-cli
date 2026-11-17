package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.ClearedContent;

/**
 * Port for the obstruction clearing of the SCRAPE step (US-06.7,
 * issue #75): removes popups, cookie banners, advertisements and, in
 * general, obstructing elements from a captured HTML5 so that
 * extraction and the stored capture refer to the main content of the
 * page.
 *
 * <p>Infrastructure provides the connector (jsoup for the static and
 * HTTP strategies; the rendered connector dismisses popups through the
 * browser BEFORE serializing the DOM). The clearing is DETERMINISTIC:
 * generic removal rules plus the task's configured page-specific
 * selectors (US-12 model, US-12.3 validation — a configured selector
 * that does not parse fails the boot, never a run). AI assistance is
 * support only (US-13, later scope); nothing here encodes
 * site-specific knowledge beyond the configured selectors.</p>
 */
public interface ObstructionClearingPort {

    /**
     * Clears the obstructing elements from the captured HTML5.
     *
     * @param html                the captured (raw) HTML5
     * @param configuredSelectors the page-specific obstructing-element
     *                            selectors configured for this run
     *                            (may be empty; never null elements)
     * @return the cleared content: the cleaned HTML5 plus the record
     *         of what was removed (US-11.4 capture metadata)
     */
    ClearedContent clear(String html, java.util.List<String> configuredSelectors);
}
