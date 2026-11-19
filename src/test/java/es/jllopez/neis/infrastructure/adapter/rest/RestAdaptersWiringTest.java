package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AddressPreflightService;
import es.jllopez.neis.application.AddressSuggestionService;
import es.jllopez.neis.application.AddressVerificationService;
import es.jllopez.neis.application.AiAssistantService;
import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.SettingsService;
import es.jllopez.neis.application.SystemInfoService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.service.UrlSyntaxValidator;
import es.jllopez.neis.testsupport.InMemoryAddressCatalogPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The REST driving adapter (US-18.1, issue #116): the remote surface
 * exists ONLY in server mode, maps requests onto the SAME application
 * use cases (no logic in controllers), and refusals surface as the
 * use cases' own exceptions (mapped to 4xx JSON by the shared advice).
 *
 * <p>The full HTTP live verification (every command group exercised
 * against the seeded store) runs against the packaged jar — this
 * class pins the WIRING: beans present when the gate is on, absent
 * when off, use-case mapping.</p>
 */
class RestAdaptersWiringTest {

    static class UseCases {  // no @Configuration: registered by the runners (lite mode); invisible to the app's component scan
        @Bean
        AddressCatalogService addressCatalogService() {
            Address verified = new Address("ADDR-001", "Source", "seeded",
                    "https://example.eu/news", AddressType.URL_NEWS);
            return new AddressCatalogService(
                    new InMemoryAddressCatalogPort(List.of(verified)),
                    new UrlSyntaxValidator());
        }

        @Bean
        AddressVerificationService addressVerificationService() {
            return mock(AddressVerificationService.class);
        }

        @Bean
        AddressPreflightService addressPreflightService() {
            return mock(AddressPreflightService.class);
        }

        @Bean
        AddressSuggestionService addressSuggestionService() {
            return mock(AddressSuggestionService.class);
        }

        @Bean
        AiAssistantService aiAssistantService() {
            AiAssistantService ai = mock(AiAssistantService.class);
            when(ai.isAvailable()).thenReturn(false);
            return ai;
        }

        @Bean
        SystemInfoService systemInfoService() {
            return mock(SystemInfoService.class);
        }

        @Bean
        ManagementInfoService managementInfoService() {
            return mock(ManagementInfoService.class);
        }
    }

    private WebApplicationContextRunner serverRunner(Class<?>... controllers) {
        var config = new java.util.ArrayList<Class<?>>(
                List.of(UseCases.class));
        config.addAll(List.of(controllers));
        return new WebApplicationContextRunner()
                .withUserConfiguration(config.toArray(new Class<?>[0]))
                .withPropertyValues("neis.server.enabled=true");
    }

    @Test
    void addressEndpointsMapOntoTheUseCases() {
        serverRunner(AddressRestController.class).run(context -> {
            assertThat(context).hasNotFailed();
            AddressRestController controller = context
                    .getBean(AddressRestController.class);
            // list mirrors the catalog
            assertThat(controller.list()).extracting(Address::id)
                    .containsExactly("ADDR-001");
            // create returns the created address (auto id when absent)
            Address created = controller.create(
                    new AddressRestController.AddressRequest(null, "New",
                            null, "https://example.eu/events",
                            "URL_EVENTS"));
            assertThat(created.id()).isNotBlank();
            assertThat(created.type()).isEqualTo(AddressType.URL_EVENTS);
            // an unknown PATH id is "no such resource" (404 via
            // NoSuchElementException — US-18.1 review finding), while
            // an unknown BODY value stays a caller 400
            assertThatThrownBy(() -> controller.show("ADDR-NOPE"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Unknown address id");
            assertThatThrownBy(() ->
                            controller.create(new AddressRestController.AddressRequest(
                                    null, "x", null, "https://example.eu/x", "WAT")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown address type");
        });
    }

    @Test
    void theRemoteSurfaceIsAbsentWithoutTheServerGate() {
        new WebApplicationContextRunner()
                .withUserConfiguration(UseCases.class,
                        AddressRestController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(AddressRestController.class);
                });
    }

    @Test
    void configEndpointsTolerateAnAbsentOverride() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.isEditable(any(String.class))).thenReturn(true);
        when(settings.effective(any(String.class))).thenReturn("static");
        // an override ABSENT for one key, PRESENT for another — Map.of
        // rejects the null and 500s the whole listing (found live)
        when(settings.overrideOf("neis.scrape.connector"))
                .thenReturn(java.util.Optional.empty());
        when(settings.overrideOf("neis.extraction.max-items"))
                .thenReturn(java.util.Optional.of("5"));
        var config = new org.springframework.boot.test.context.runner
                .ApplicationContextRunner()
                .withUserConfiguration(UseCases.class, ConfigRestController.class)
                .withBean(SettingsService.class, () -> settings)
                .withPropertyValues("neis.server.enabled=true");
        config.run(context -> {
            assertThat(context).hasNotFailed();
            var body = context.getBean(ConfigRestController.class).config();
            var editable = (java.util.Map<?, ?>) body.get("editable");
            var scrape = (java.util.Map<?, ?>) editable.get("neis.scrape.connector");
            assertThat(scrape.get("value")).isEqualTo("static");
            assertThat(scrape.containsKey("override")).isTrue();
            assertThat(scrape.get("override")).isNull();
            var maxItems = (java.util.Map<?, ?>) editable.get("neis.extraction.max-items");
            assertThat(maxItems.get("override")).isEqualTo("5");
        });
    }

    @Test
    void aiStatusReflectsTheIntegrationPointsAvailability() {
        serverRunner(AiSystemRestController.class).run(context -> {
            assertThat(context).hasNotFailed();
            AiSystemRestController controller =
                    context.getBean(AiSystemRestController.class);
            // the mock integration point is unavailable: the endpoint
            // reports it instead of failing the boot
            assertThat(controller.aiStatus())
                    .containsEntry("available", false);
            assertThatThrownBy(() -> controller.ask(
                            new AiSystemRestController.AskRequest("hello")))
                    .isInstanceOf(
                            org.springframework.web.server.ResponseStatusException.class);
        });
    }

    @Test
    void callerErrorsMapTo4xxAndDeliberateStatusesSurvive() {
        var advice = new GlobalApiExceptionHandler();
        // malformed body → 400 (was: catch-all 500, found live)
        assertThat(advice.unreadableBody(
                        new org.springframework.http.converter.HttpMessageNotReadableException(
                                "bad json", new org.springframework.http.HttpInputMessage() {
                            public java.io.InputStream getBody() { return null; }
                            public org.springframework.http.HttpHeaders getHeaders() { return null; }
                        })).getStatusCode().value()).isEqualTo(400);
        // a deliberate ResponseStatusException keeps its status
        // (was: catch-all clobbered it to 500, found live)
        assertThat(advice.responseStatus(new org.springframework.web.server
                        .ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "AI unavailable")).getStatusCode().value()).isEqualTo(503);
        // caller refusals and unknown ids
        assertThat(advice.badRequest(new IllegalArgumentException("nope"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(advice.notFound(new java.util.NoSuchElementException("gone"))
                .getStatusCode().value()).isEqualTo(404);
        // internal failures never leak the exception message
        assertThat(advice.internal(new RuntimeException("boom")).getBody()
                .message()).doesNotContain("boom");
    }
}
