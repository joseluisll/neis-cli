package es.jllopez.neis.domain.model;

/**
 * An address tracked by NEIS: a URL plus its listing kind
 * (requirement US-03.1, issue #18).
 *
 * <p>MVP: this is the in-memory seed catalog model; persistence
 * (US-03.3, issue #20) is out of MVP scope.</p>
 */
public record Address(String id, String name, String url, AddressType type) {

    /** Kind of items this address lists: "news" or "event". */
    public String itemKind() {
        return type == AddressType.URL_NEWS ? "news" : "event";
    }
}
