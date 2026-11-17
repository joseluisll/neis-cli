package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.application.PipelineService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connector (driven adapter): the SIMPLIFY side-table source (US-07.6,
 * US-11.4) — derives the hashed-URL placeholder map of the lean tier
 * from the cleared input by re-running the DOM prune (the SAME
 * whitelisting/hash rules the element catalog uses, so the hashes in
 * the table match the hashes in the rendered artifact). Implements the
 * application's {@link PipelineService.SimplifySideTableSource}
 * (infrastructure -> application is the allowed direction).
 */
@Component
public class PrunedTreeSideTableSource implements PipelineService.SimplifySideTableSource {

    private final JsoupDomAnalyzer domAnalyzer;

    public PrunedTreeSideTableSource(JsoupDomAnalyzer domAnalyzer) {
        this.domAnalyzer = domAnalyzer;
    }

    @Override
    public Map<String, String> sideTable(String clearedHtml) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        domAnalyzer.prune(clearedHtml, placeholders);
        return placeholders;
    }
}
