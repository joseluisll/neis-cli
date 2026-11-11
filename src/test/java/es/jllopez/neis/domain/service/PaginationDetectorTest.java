package es.jllopez.neis.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pagination detection heuristics (requirement US-12.4, issue #67).
 */
class PaginationDetectorTest {

    private final PaginationDetector detector = new PaginationDetector();

    @Test
    void detectsPageNumberUrlPatterns() {
        String html = "<ul><li><a href=\"?sortBy=publicationDate&pageNumber=2\">2</a></li></ul>";
        assertTrue(detector.detect(html).paginated());
    }

    @Test
    void detectsRelNext() {
        assertTrue(detector.detect("<a rel=\"next\" href=\"/page/2\">").paginated());
    }

    @Test
    void detectsPagerMarkup() {
        assertTrue(detector.detect("<div class=\"pagination\"><a href=\"#\">Next</a></div>").paginated());
    }

    @Test
    void plainPageIsNotPaginated() {
        assertFalse(detector.detect("<html><body><h1>News</h1><p>Nothing here</p></body></html>").paginated());
    }

    @Test
    void classContainingPageAloneIsNotPagination() {
        // regression: "portal-page support-news" must not count as pager markup
        assertFalse(detector.detect("<div class=\"portal-page support-news\">...</div>").paginated());
    }

    @Test
    void detectsPageNumberInEscapedHrefs() {
        // jsoup serializes & as &amp; inside attribute values
        String html = "<a href=\"?sortBy=date&amp;pageNumber=2&amp;pageSize=10\">2</a>";
        assertTrue(detector.detect(html).paginated());
    }

    @Test
    void detectsPaginatorMarkup() {
        // regression: the portal's eui-paginator must count as pagination
        assertTrue(detector.detect("<eui-paginator class=\"eui-paginator\"></eui-paginator>").paginated());
    }

    @Test
    void blankInputIsNotPaginated() {
        assertFalse(detector.detect(null).paginated());
        assertFalse(detector.detect("  ").paginated());
    }
}
