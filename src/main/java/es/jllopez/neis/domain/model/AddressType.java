package es.jllopez.neis.domain.model;

/**
 * Type of an address, constrained to the two listing kinds NEIS tracks
 * (requirement US-03.1, issue #18).
 */
public enum AddressType {
    /** The address lists news items. */
    URL_NEWS,
    /** The address lists events. */
    URL_EVENTS
}
