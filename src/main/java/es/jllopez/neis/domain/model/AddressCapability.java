package es.jllopez.neis.domain.model;

/**
 * A listing capability of an address, detected by the ANALYZE block and
 * flagged onto the address (FILTER requirement, US-17). Blocks of a chain
 * execute their page-dependent functionality ONLY when the address has
 * been flagged with the corresponding capability.
 */
public enum AddressCapability {

    /** The listing is paginated (pager controls or page-number URLs). */
    PAGINATION,

    /** The listing offers filter controls to narrow it (facets, selects). */
    FILTERING
}
