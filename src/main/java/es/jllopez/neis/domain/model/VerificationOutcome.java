package es.jllopez.neis.domain.model;

/**
 * The outcome of one verification run (US-04.2, issue #22): the
 * resulting state, the ISO-8601 instant of the check, the HTTP status
 * code when one was received (0 when the check failed before any
 * response) and a short human-readable detail.
 */
public record VerificationOutcome(VerificationStatus status, String checkedAt,
                                  int statusCode, String detail) {
}
