package es.jllopez.neis.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.jllopez.neis.domain.port.AiAssistantPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The AI integration point is optional at runtime: with no connector
 * on the classpath wiring the service must report unavailable and
 * refuse completion; with a connector it must delegate to it.
 */
class AiAssistantServiceTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AiAssistantPort> providerReturning(AiAssistantPort port) {
        ObjectProvider<AiAssistantPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return provider;
    }

    @Test
    void unavailableWhenNoConnectorProvided() {
        AiAssistantService service = new AiAssistantService(providerReturning(null));

        assertFalse(service.isAvailable());
        assertThrows(IllegalStateException.class, () -> service.complete("hi"));
    }

    @Test
    void delegatesToConnectorWhenProvided() {
        AiAssistantPort connector = prompt -> "answer:" + prompt;
        AiAssistantService service = new AiAssistantService(providerReturning(connector));

        assertTrue(service.isAvailable());
        assertEquals("answer:hello", service.complete("hello"));
    }
}
