package es.jllopez.neis.domain.model;

/**
 * Result of pagination detection over a simplified HTML5 page
 * (requirement US-12.4, issue #67 — detection only in the MVP; iteration
 * across pages is out of MVP scope).
 */
public record PaginationInfo(boolean paginated, String evidence) {

    public static PaginationInfo notPaginated() {
        return new PaginationInfo(false, "No pagination controls or URL patterns detected");
    }
}
