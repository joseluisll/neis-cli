package es.jllopez.neis.infrastructure.adapter.mcp;

import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.SettingsService;
import es.jllopez.neis.application.SystemInfoService;
import es.jllopez.neis.domain.model.SystemInfo;
import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The MCP driving adapter (US-18.3/#118 + US-18.4/#119): a CURATED,
 * USER-ORIENTED tool set — workflow-level granularity, AT MOST 10
 * tools, NO AI functionality (owner decisions 2026-11-19; the full
 * operation surface stays on REST, #116). The tools delegate to the
 * SAME application use cases as the CLI/REST adapters — no logic
 * duplication across driving adapters.
 *
 * <p>Registered through Spring AI's {@code @McpTool} annotation
 * scanner (only in server mode; the MCP auto-configuration itself is
 * gated by {@code spring.ai.mcp.server.enabled} tracking
 * {@code neis.server.enabled}). Every tool maps to its REST
 * counterpart(s) — the catalog page (#120) documents the pairing.</p>
 */
@Component
@ConditionalOnServerEnabled
public class NeisMcpTools {

    private final SystemInfoService system;
    private final ManagementInfoService management;
    private final SettingsService settings;

    public NeisMcpTools(SystemInfoService system,
                        ManagementInfoService management,
                        SettingsService settings) {
        this.system = system;
        this.management = management;
        this.settings = settings;
    }

    /**
     * Tool 1 of 10 — the agent's entry point: version, scrape
     * connector mode, task types, address counts. REST counterpart:
     * {@code GET /api/system} + {@code GET /api/dashboard}
     * (+ the scrape connector from the config allowlist).
     */
    @McpTool(name = "neis_system_info",
            description = "NEIS entry point: version, scrape connector mode, "
                    + "known task types, address counts (registered/verified) "
                    + "and execution counts. Call this first to orient.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, idempotentHint = true))
    public Map<String, Object> systemInfo() {
        SystemInfo info = system.getSystemInfo();
        ManagementInfoService.Dashboard dash = management.dashboard();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", info.name());
        out.put("description", info.description());
        out.put("version", info.version());
        out.put("scrapeConnector", settings.effective("neis.scrape.connector"));
        out.put("taskTypes", dash.taskTypes());
        out.put("registeredAddresses", dash.addresses());
        out.put("verifiedAddresses", dash.verifiedAddresses());
        out.put("chains", dash.chains());
        out.put("executions", dash.executions());
        return out;
    }
}
