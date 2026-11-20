package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Consistent JSON error mapping for the REST surface (US-18.1, issue
 * #116): caller mistakes are 4xx with a message that names the
 * problem, internal failures are 5xx that never leak stack traces.
 * Controllers stay free of try/catch — the use cases' own exceptions
 * (IllegalArgumentException for refusals, NoSuchElementException for
 * unknown ids) map here, once.
 */
@RestControllerAdvice
@ConditionalOnServerEnabled
public class GlobalApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    record ApiError(int status, String error, String message) {
        static ApiError of(HttpStatus status, String message) {
            return new ApiError(status.value(), status.getReasonPhrase(), message);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler({NoSuchElementException.class})
    public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
        String message = e.getMessage() == null
                ? "Not found" : e.getMessage();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND, message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT, e.getMessage()));
    }

    // a malformed/mistyped request body is a CALLER error, not an
    // internal failure (found live: a bare JSON string where the
    // SettingValue record expects {"value": ...} surfaced as 500)
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        // US-18.1 review finding 3: the raw Jackson cause carries
        // internal type names — the 400 states the problem generically
        // (the advice's own no-internals contract); the detail lives
        // in the server log.
        log.warn("Malformed request body rejected: {}",
                e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST,
                        "Malformed request body — check the JSON syntax "
                                + "and the documented field types"));
    }

    // ResponseStatusException (e.g. the AI endpoint's 503 refusal) is
    // itself an Exception: without this handler the catch-all below
    // clobbers every deliberate status into a 500 (found live)
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiError> responseStatus(
            org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiError.of(HttpStatus.valueOf(e.getStatusCode().value()),
                        e.getReason() == null ? e.getMessage() : e.getReason()));
    }

    // an unknown path (and an unknown method on a known path) is a
    // CALLER error, not an internal failure: Spring 7 throws
    // NoResourceFoundException from the static-resource handler for
    // unmatched routes, which used to fall into the catch-all below
    // and answer 500 with an ERROR stack for a trivial mistake
    // (found live, issue #143)
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> unknownPath(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND,
                        "No such endpoint: " + e.getResourcePath()));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMethod(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of(HttpStatus.METHOD_NOT_ALLOWED,
                        e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception e) {
        log.error("REST request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal error — see the server log"));
    }
}
