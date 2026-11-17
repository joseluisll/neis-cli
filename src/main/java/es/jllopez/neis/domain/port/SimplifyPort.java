package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.SimplifiedContent;

/**
 * Port for the SIMPLIFY step: cleanses an HTML5 input down to action and
 * data elements, with a 100 KB output cap
 * (requirements US-07.1..US-07.3, issues #30-#32).
 *
 * <p>US-07.4 (issue #76): the port also owns the output-compliance
 * contract — {@link #verifySyntax(String)} re-parses a simplified HTML5
 * document with error tracking and refuses forbidden non-data elements
 * (scripts, styles, media, popups, embedded objects); a failed check is
 * an error of the task run. Every connector behind this port carries
 * the same guarantee (US-01.4 replaceability).</p>
 */
public interface SimplifyPort {

    SimplifiedContent simplify(String rawHtml);

    /**
     * US-07.6 (issue #89): renders the simplified HTML5 from the PRUNED
     * ELEMENT TREE (US-06.9) at the requested tier
     * ({code full}/{@code economy}/{@code lean}). The pruned tree is derived from the same
     * input the DOM analyzer prunes (Skyvern's isDropped semantics);
     * the rendered output is compact, syntactically correct HTML5 and
     * carries the hashed-URL side table in the result's webpage
     * metadata (US-11.4). The default (no-arg) {@link #simplify} is
     * the {@code full} variant.
     *
     * @param rawHtml the cleared HTML5 input
     * @param variant one of {@code full}, {@code economy}, {@code lean}
     * @throws IllegalArgumentException on an unknown variant
     */
    SimplifiedContent simplify(String rawHtml, String variant);

    /**
     * US-07.4: verifies that an HTML5 document (typically a SIMPLIFY
     * output about to be stored) is syntactically valid and free of
     * forbidden non-data elements.
     *
     * @throws RuntimeException when the document does not parse as
     *         valid HTML5 or contains a non-data element
     */
    void verifySyntax(String html);
}
