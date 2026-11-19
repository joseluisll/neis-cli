package es.jllopez.neis.infrastructure.adapter.rest;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Maps the use cases' unknown-id refusal onto the REST contract
 * (US-18.1 review finding, issue #116): the application services
 * signal an unknown resource id with {@link IllegalArgumentException},
 * which the shared advice maps to 400 — right for a malformed BODY,
 * wrong for a GET/PUT/DELETE of an id taken from the URL path, where
 * the caller mistake is "no such resource" (404, the contract the
 * jobs/content endpoints already follow).
 *
 * <p>Wrap ONLY path-variable call sites: a refusal on a request-body
 * value (e.g. an unknown address TYPE on create) is a 400 and must
 * stay one. The translation is pure driving-adapter mapping — the
 * use cases' exception type is unchanged for the shell.</p>
 */
final class PathLookup {

    private PathLookup() {
    }

    static <T> T or404(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException unknownId) {
            throw new NoSuchElementException(unknownId.getMessage());
        }
    }

    /** Void variant (deletes). */
    static void or404Void(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException unknownId) {
            throw new NoSuchElementException(unknownId.getMessage());
        }
    }
}
