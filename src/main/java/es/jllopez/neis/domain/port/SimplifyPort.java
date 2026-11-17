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
     * US-07.4: verifies that an HTML5 document (typically a SIMPLIFY
     * output about to be stored) is syntactically valid and free of
     * forbidden non-data elements.
     *
     * @throws RuntimeException when the document does not parse as
     *         valid HTML5 or contains a non-data element
     */
    void verifySyntax(String html);
}
