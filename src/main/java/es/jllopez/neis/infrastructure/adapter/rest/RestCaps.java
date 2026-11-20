package es.jllopez.neis.infrastructure.adapter.rest;

/**
 * The REST surface's server-side response caps (US-18.1 review
 * finding 1, issue #127): a client limit is bounded by a server cap —
 * an unbounded table view is never serializable by one request. The
 * bulk/offline surface is the CLI and the US-18.8 export, not an
 * uncapped HTTP table.
 */
final class RestCaps {

    /** The maximum number of records any table/list endpoint returns. */
    static final int TABLE_CAP = 500;

    private RestCaps() {
    }
}
