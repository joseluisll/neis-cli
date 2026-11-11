package es.jllopez.neis.domain.model;

/**
 * Verification state of a registered address (US-04.2, issue #22).
 * Typed in the domain here, stored as its name in the
 * {@code addresses.verification_status} column of schema V1 (whose
 * CHECK constraint already lists these exact values).
 */
public enum VerificationStatus {
    /** Not yet checked by the verification process. */
    UNVERIFIED,
    /** The verification process reached the URL successfully. */
    VERIFIED,
    /** The last verification attempt failed. */
    FAILED
}
