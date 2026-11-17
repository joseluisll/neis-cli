package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AiAssistantService;
import es.jllopez.neis.application.ChainService;
import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.application.ExecutionService;
import es.jllopez.neis.application.ManagementInfoService;
import es.jllopez.neis.application.NewsEventService;
import es.jllopez.neis.application.ScheduleProperties;
import es.jllopez.neis.domain.port.SelectorStorePort;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: the {@code manage} command (US-16.1, issue #78)
 * opens the unified management interface (US-16, issue #77) — one
 * interactive screen with the dashboard (US-16.2) and the management
 * sections: addresses (the US-09.1 screen), chains (US-16.5 chain
 * part), executions with re-run (US-16.3), the repository browser
 * (US-16.4) and the configuration overview (US-16.5). Runs on the
 * shell's own JLine terminal (no new dependency), works interactively
 * and over piped input (clean EOF exit, script-verifiable).
 */
@Component
@CommandGroup(name = "Management", description = "Unified management interface (US-16)")
public class ManagementCommands {

    private final AddressCatalogService catalog;
    private final ChainService chains;
    private final ExecutionService executions;
    private final NewsEventService newsEvents;
    private final ContentStoreService contentStore;
    private final ManagementInfoService info;
    private final ScheduleProperties schedules;
    private final AiAssistantService ai;
    private final SelectorStorePort selectorStore;
    private final es.jllopez.neis.application.PreconfiguredSelectorProperties preconfiguredSelectors;
    private final es.jllopez.neis.application.PreconfiguredFilterProperties preconfiguredFilters;
    private final es.jllopez.neis.application.SettingsService settings;
    private final es.jllopez.neis.infrastructure.adapter.config.SettingsOverrideApplier settingsApplier;
    private final org.springframework.core.env.Environment environment;
    private final Terminal terminal;

    public ManagementCommands(AddressCatalogService catalog,
                              ChainService chains,
                              ExecutionService executions,
                              NewsEventService newsEvents,
                              ContentStoreService contentStore,
                              ManagementInfoService info,
                              ScheduleProperties schedules,
                              AiAssistantService ai,
                              SelectorStorePort selectorStore,
                              es.jllopez.neis.application.PreconfiguredSelectorProperties preconfiguredSelectors,
                              es.jllopez.neis.application.PreconfiguredFilterProperties preconfiguredFilters,
                              es.jllopez.neis.application.SettingsService settings,
                              es.jllopez.neis.infrastructure.adapter.config.SettingsOverrideApplier settingsApplier,
                              org.springframework.core.env.Environment environment,
                              Terminal terminal) {
        this.catalog = catalog;
        this.chains = chains;
        this.executions = executions;
        this.newsEvents = newsEvents;
        this.contentStore = contentStore;
        this.info = info;
        this.schedules = schedules;
        this.ai = ai;
        this.selectorStore = selectorStore;
        this.preconfiguredSelectors = preconfiguredSelectors;
        this.preconfiguredFilters = preconfiguredFilters;
        this.settings = settings;
        this.settingsApplier = settingsApplier;
        this.environment = environment;
        this.terminal = terminal;
    }

    @Command(name = {"manage"}, group = "Management",
            description = "Open the unified management interface: dashboard, addresses, "
                    + "chains, executions, repository, configuration and logs (US-16)")
    public void manage() {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        new ManagementTui(catalog, chains, executions, newsEvents, contentStore,
                info, schedules, ai, selectorStore,
                preconfiguredSelectors, preconfiguredFilters,
                new AddressTuiCommands.JLineConsoleIO(reader),
                settings, settingsApplier, environment).run();
    }
}
