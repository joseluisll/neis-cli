package es.jllopez.neis.infrastructure.adapter.mcp;

import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.SettingsService;
import es.jllopez.neis.application.SystemInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MCP driving adapter (US-18.3, issue #118): the tool beans exist
 * ONLY in server mode (the same gate as the REST surface), and the
 * MCP auto-configuration follows {@code neis.server.enabled} through
 * the application.yml placeholder. The full protocol verification
 * (initialize → tools/list → tools/call) runs live against the
 * packaged jar with a real MCP client — this class pins the WIRING.
 */
class McpAdaptersWiringTest {

    static class UseCases {  // no @Configuration: registered by the runners (lite mode); invisible to the app's component scan
        @Bean
        SystemInfoService systemInfoService() {
            SystemInfoService s = mock(SystemInfoService.class);
            when(s.getSystemInfo()).thenReturn(new es.jllopez.neis.domain.model
                    .SystemInfo("neis", "News and Events Information System",
                    "0.1.0-SNAPSHOT", "21"));
            return s;
        }

        @Bean
        ManagementInfoService managementInfoService() {
            ManagementInfoService m = mock(ManagementInfoService.class);
            when(m.dashboard()).thenReturn(new ManagementInfoService.Dashboard(
                    1, 1, 0, 0, 1, List.of("core-chain-ssafp"),
                    List.of("WEBSCRAPE"), 0, 0, 0, null, List.of(), 0, 0,
                    List.of(), false));
            return m;
        }

        @Bean
        SettingsService settingsService() {
            SettingsService s = mock(SettingsService.class);
            when(s.effective(any(String.class))).thenReturn("static");
            return s;
        }
    }

    @Test
    void theToolAdapterExistsOnlyInServerMode() {
        var server = new ApplicationContextRunner()
                .withUserConfiguration(UseCases.class, NeisMcpTools.class)
                .withPropertyValues("neis.server.enabled=true");
        server.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NeisMcpTools.class);
            // the entry-point tool resolves against the use cases
            Map<String, Object> info =
                    context.getBean(NeisMcpTools.class).systemInfo();
            assertThat(info).containsEntry("scrapeConnector", "static");
            assertThat(info).containsEntry("registeredAddresses", 1);
        });

        new ApplicationContextRunner()
                .withUserConfiguration(UseCases.class, NeisMcpTools.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(NeisMcpTools.class);
                });
    }

    @Test
    void theMcpAutoConfigurationFollowsTheServerGate() {
        // the application.yml placeholder: spring.ai.mcp.server.enabled
        // must track neis.server.enabled (default FALSE — one-shot CLI
        // invocations never boot MCP)
        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer());
        runner.run(context -> assertThat(
                context.getEnvironment().getProperty(
                        "spring.ai.mcp.server.enabled"))
                .isEqualTo("false"));
        runner.withPropertyValues("neis.server.enabled=true").run(context ->
                assertThat(context.getEnvironment().getProperty(
                        "spring.ai.mcp.server.enabled")).isEqualTo("true"));
    }
}
