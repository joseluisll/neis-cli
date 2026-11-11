package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    private TaskWorkService service(MvpChainService chain) {
        return new TaskWorkService(chain, mock(AddressCatalogService.class));
    }

    private MvpChainService chainReturning(MvpChainResult result) {
        MvpChainService chain = mock(MvpChainService.class);
        when(chain.run(any(Address.class), anyInt(), anyInt())).thenReturn(result);
        return chain;
    }

    @Test
    void printAcceptsMaxItemsAndMaxPagesAndPassesThemThrough() {
        MvpChainResult result = mock(MvpChainResult.class);
        when(result.items()).thenReturn(List.of());
        when(result.pagesScraped()).thenReturn(1);
        when(result.analysis()).thenReturn(null);
        when(result.capabilities()).thenReturn(java.util.Set.of());
        when(result.scrapeArtifact()).thenReturn("s");
        when(result.simplifyArtifact()).thenReturn("s");
        MvpChainService chain = mock(MvpChainService.class);
        when(chain.run(any(Address.class), anyInt(), anyInt())).thenReturn(result);

        String outcome = service(chain).execute("PRINT", address,
                Map.of("max-items", "5", "max-pages", "2"));

        verify(chain).run(address, 5, 2);
        assertTrue(outcome.startsWith("CHAIN:"));
    }

    @Test
    void anUnknownParameterKeyIsRefusedNotIgnored() {
        // A typo like 'max-ites' must not become silent dead
        // configuration (US-08 review disqualifier).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(mock(MvpChainService.class)).execute("PRINT", address,
                        Map.of("max-ites", "10")));
        assertTrue(e.getMessage().contains("max-ites"));
        assertTrue(e.getMessage().contains("only"));
    }

    @Test
    void aNonIntegerParameterValueIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(mock(MvpChainService.class)).execute("CHAIN", address,
                        Map.of("max-items", "abc")));
        assertTrue(e.getMessage().contains("must be an integer"));
    }

    @Test
    void anOutOfRangeParameterValueIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service(mock(MvpChainService.class)).execute("PRINT", address,
                        Map.of("max-items", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> service(mock(MvpChainService.class)).execute("PRINT", address,
                        Map.of("max-pages", "101")));
    }

    @Test
    void parameterlessPrintUsesTheDefaults() {
        MvpChainResult result = mock(MvpChainResult.class);
        when(result.items()).thenReturn(List.of());
        when(result.pagesScraped()).thenReturn(1);
        when(result.analysis()).thenReturn(null);
        when(result.capabilities()).thenReturn(java.util.Set.of());
        when(result.scrapeArtifact()).thenReturn("s");
        when(result.simplifyArtifact()).thenReturn("s");
        MvpChainService chain = mock(MvpChainService.class);
        when(chain.run(any(Address.class), anyInt(), anyInt())).thenReturn(result);

        service(chain).execute("PRINT", address, Map.of());

        verify(chain).run(address, 50, 10);
    }

    @Test
    void otherTaskTypesStillRefuseAnyParameter() {
        TaskWorkService work = service(mock(MvpChainService.class));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> work.execute("WEBSCRAPE", address, Map.of("max-items", "5")));
        assertTrue(e.getMessage().contains("takes no parameters"));
    }
}
