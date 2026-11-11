package es.jllopez.neis.domain.model;

import java.util.Set;

/**
 * An address tracked by NEIS: a URL plus its listing kind, a free text
 * description and its verification state (requirements US-03.1/#18 and
 * US-04/#21-23).
 *
 * <p>Verification (US-04.2): an address starts UNVERIFIED; the
 * verification process marks it VERIFIED or FAILED together with the
 * ISO-8601 instant of the check. Executors only use addresses that are
 * syntactically valid (US-04.1) AND verified (US-04.3).</p>
 *
 * <p>Capabilities (US-17): the ANALYZE block detects what the listing
 * page itself offers (pagination, filtering) and FLAGS those
 * capabilities onto the address. Downstream blocks execute their
 * page-dependent functionality only on addresses flagged with the
 * corresponding capability. Flags are a per-run outcome of ANALYZE.</p>
 *
 * <p>Address corrections (US-13.7): the analysis phase may propose
 * corrections to the registered URL — AI-driven, never static rules —
 * so the registered address and the stored selectors form a perfect
 * match for pagination, filtering and extraction.</p>
 */
public record Address(String id, String name, String description, String url,
                      AddressType type, Set<AddressCapability> capabilities,
                      VerificationStatus verificationStatus, String verifiedAt) {

    public Address(String id, String name, String url, AddressType type) {
        this(id, name, null, url, type, Set.of());
    }

    public Address(String id, String name, String description, String url, AddressType type) {
        this(id, name, description, url, type, Set.of());
    }

    public Address(String id, String name, String description, String url,
                   AddressType type, Set<AddressCapability> capabilities) {
        this(id, name, description, url, type, capabilities,
                VerificationStatus.UNVERIFIED, null);
    }

    /** Canonical constructor: a missing verification state is UNVERIFIED. */
    public Address {
        if (verificationStatus == null) {
            verificationStatus = VerificationStatus.UNVERIFIED;
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /** Copy of this address flagged with the given detected capabilities. */
    public Address withCapabilities(Set<AddressCapability> detected) {
        return new Address(id, name, description, url, type, Set.copyOf(detected),
                verificationStatus, verifiedAt);
    }

    /**
     * Copy of this address with a corrected URL. ANALYZE may propose
     * corrections to the registered address (canonical listing URL
     * revealed by the page, pager-derived URL pattern inconsistencies) —
     * AI-driven, never a static rewrite rule.
     */
    public Address withUrl(String correctedUrl) {
        return new Address(id, name, description, correctedUrl, type, capabilities,
                verificationStatus, verifiedAt);
    }

    /**
     * Copy of this address with a new verification outcome (US-04.2,
     * issue #22): the status and the ISO-8601 instant of the check.
     */
    public Address withVerification(VerificationStatus newStatus, String checkedAt) {
        return new Address(id, name, description, url, type, capabilities,
                newStatus, checkedAt);
    }

    /** True when ANALYZE flagged this address with the capability. */
    public boolean hasCapability(AddressCapability capability) {
        return capabilities.contains(capability);
    }

    /** True when the address passed the verification process (US-04.3). */
    public boolean isVerified() {
        return verificationStatus == VerificationStatus.VERIFIED;
    }

    /** Kind of items this address lists: "news" or "event". */
    public String itemKind() {
        return type == AddressType.URL_NEWS ? "news" : "event";
    }
}
