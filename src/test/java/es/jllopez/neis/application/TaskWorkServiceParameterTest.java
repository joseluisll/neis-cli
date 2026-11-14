package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.PageAnalysis;
import es.jllopez.neis.domain.model.PaginationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-task parameter boundary (US-08.3, issue #35): PRINT/CHAIN
 * accept ONLY max-items (1..10000) and max-pages (1..100) — an unknown
 * key, a non-integer or an out-of-range value is REFUSED, never
 * silently ignored (fail-open disqualifier of the US-08 review). The
 * other task types refuse any parameters at all.
 */
class TaskWorkServiceParameterTest {

    private final Address address = new Address("ADDR-001", "Source",
            "https://example.eu/news", AddressType.URL_NEWS);

    /** A minimal real result: the PRINT report renders it, so a mock
     *  record (null everything) would not survive the render. */
    private PipelineResult result() {
        return new PipelineResult(address, "scrape", "simplified",
                1, 1, new PaginationInfo(false, null),
                PageAnalysis.fallback("news", "test"), "ul li", "fallback",
                List.of(), Set.of(), "", null, 1, null,
                null, List.of(), "skipped", "not flagged",
                null, null, null, List.of(), null, null);
    }

    private TaskWorkService service(PipelineService pipeline) {
        return new TaskWorkService(pipeline, mock(AddressCatalogService.class), null);
    }

    private PipelineService pipelineReturning(PipelineResult result) {
        PipelineService pipeline = mock(PipelineService.class);
        when(pipeline.run(any(Address.class), anyInt(), anyInt(), any())).thenReturn(result);
        return pipeline;
    }

    @Test
    void printAcceptsMaxItemsAndMaxPagesAndPassesThemThrough() {
        PipelineService pipeline = pipelineReturning(result());

        String outcome = service(pipeline).execute("PRINT", address,
                Map.of("max-items", "5", "max-pages", "2"));

        verify(pipeline).run(address, 5, 2, null);
        assertTrue(outcome.startsWith("CHAIN:"));
    }

    @Test
    void anUnknownParameterKeyIsRefusedNotIgnored() {
        // A typo like 'max-ites' must not become silent dead
        // configuration (US-08 review disqualifier).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(mock(PipelineService.class)).execute("PRINT", address,
                        Map.of("max-ites", "10")));
        assertTrue(e.getMessage().contains("max-ites"));
        assertTrue(e.getMessage().contains("only"));
    }

    @Test
    void aNonIntegerParameterValueIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(mock(PipelineService.class)).execute("CHAIN", address,
                        Map.of("max-items", "abc")));
        assertTrue(e.getMessage().contains("must be an integer"));
    }

    @Test
    void anOutOfRangeParameterValueIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service(mock(PipelineService.class)).execute("PRINT", address,
                        Map.of("max-items", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> service(mock(PipelineService.class)).execute("PRINT", address,
                        Map.of("max-pages", "101")));
    }

    @Test
    void parameterlessPrintUsesTheDefaults() {
        PipelineService pipeline = pipelineReturning(result());

        service(pipeline).execute("PRINT", address, Map.of());

        verify(pipeline).run(address, 50, 10, null);
    }

    @Test
    void theChainStepTitlesTheReportWithTheChainName() {
        PipelineService pipeline = pipelineReturning(result());

        String outcome = service(pipeline).execute("PRINT", address,
                Map.of(), "core-chain-ssafp");

        assertTrue(outcome.contains("report="));
    }

    @Test
    void otherTaskTypesStillRefuseAnyParameter() {
        TaskWorkService work = service(mock(PipelineService.class));
        // SIMPLIFY and ANALYZE take no parameters at all (WEBSCRAPE
        // accepts max-items since US-12.5).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> work.execute("SIMPLIFY", address, Map.of("max-items", "5")));
        assertTrue(e.getMessage().contains("takes no such parameter"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> work.execute("ANALYZE", address, Map.of("selector", "ul li")));
        assertTrue(e2.getMessage().contains("takes no such parameter"));
    }

    @Test
    void webScrapeAcceptsBoundedMaxItemsAndRefusesBrokenOnes() {
        TaskWorkService work = service(mock(PipelineService.class));
        // Bounds follow PRINT's max-items: 1..10000, integer only.
        IllegalArgumentException nonInt = assertThrows(IllegalArgumentException.class,
                () -> work.execute("WEBSCRAPE", address, Map.of("max-items", "many")));
        assertTrue(nonInt.getMessage().contains("must be an integer"));
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> work.execute("WEBSCRAPE", address, Map.of("max-items", "0")));
        assertTrue(zero.getMessage().contains("between 1 and 10000"));
        IllegalArgumentException huge = assertThrows(IllegalArgumentException.class,
                () -> work.execute("WEBSCRAPE", address, Map.of("max-items", "10001")));
        assertTrue(huge.getMessage().contains("between 1 and 10000"));
    }

    @Test
    void filterRefusesAHalfSpecAndAnUnknownKey() {
        TaskWorkService work = service(mock(PipelineService.class));
        // Only one of the two keys is a broken definition, not a half-spec.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> work.execute("FILTER", address, Map.of("field", "Programme")));
        assertTrue(e.getMessage().contains("both"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> work.execute("FILTER", address, Map.of("spec-file", "x.json")));
        assertTrue(e2.getMessage().contains("takes no such parameter"));
    }
}
