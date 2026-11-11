package es.jllopez.neis.domain.model;

import java.util.Set;

/**
 * An address tracked by NEIS: a URL plus its listing kind and a free
 * text description (requirement US-03.1, issue #18).
 *
 * <p>MVP: this is the in-memory seed catalog model; persistence
 * (US-03.3, issue #20) is out of MVP scope.</p>
 *
 * <p>Capabilities (US-17): the ANALYZE block detects what the listing
 * page itself offers (pagination, filtering) and FLAGS those
 * capabilities onto the address. Downstream blocks execute their
 * page-dependent functionality only on addresses flagged with the
 * corresponding capability. The catalog seeds ship unflagged; flags are
 * a per-run outcome of ANALYZE.</p>
 *
 * <p>Address corrections (US-13.7): the analysis phase may propose
 * corrections to the registered URL — AI-driven, never static rules —
 * so the registered address and the stored selectors form a perfect
 * match for pagination, filtering and extraction.</p>
 */
public record Address(String id, String name, String description, String url,
                      AddressType type, Set<AddressCapability> capabilities) {

    public Address(String id, String name, String url, AddressType type) {
        this(id, name, null, url, type, Set.of());
    }

    public Address(String id, String name, String description, String url, AddressType type) {
        this(id, name, description, url, type, Set.of());
    }

    /** Copy of this address flagged with the given detected capabilities. */
    public Address withCapabilities(Set<AddressCapability> detected) {
        return new Address(id, name, description, url, type, Set.copyOf(detected));
    }

    /**
     * Copy of this address with a corrected URL. ANALYZE may propose
     * corrections to the registered address (canonical listing URL
     * revealed by the page, pager-derived URL pattern inconsistencies) —
     * AI-driven, never a static rewrite rule.
     */
    public Address withUrl(String correctedUrl) {
        return new Address(id, name, description, correctedUrl, type, capabilities);
    }

    /** True when ANALYZE flagged this address with the capability. */
    public boolean hasCapability(AddressCapability capability) {
        return capabilities.contains(capability);
    }

    /** Kind of items this address lists: "news" or "event". */
    public String itemKind() {
        return type == AddressType.URL_NEWS ? "news" : "event";
    }
}
