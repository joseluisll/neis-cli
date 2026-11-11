package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.SimplifiedContent;

/**
 * Port for the SIMPLIFY step: cleanses an HTML5 input down to action and
 * data elements, with a 100 KB output cap
 * (requirements US-07.1..US-07.3, issues #30-#32).
 */
public interface SimplifyPort {

    SimplifiedContent simplify(String rawHtml);
}
