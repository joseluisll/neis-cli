package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.SystemInfoService;
import es.jllopez.neis.domain.model.SystemInfo;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: Spring Shell commands. Thin — delegates to
 * application use cases.
 *
 * <p>Spring Shell 4 command model: a {@code @Component} class whose
 * methods are annotated with {@code @Command}; the application class
 * lists it in {@code @EnableCommand}.</p>
 */
@Component
@CommandGroup(name = "NEIS", description = "NEIS application commands")
public class NeisCommands {

    private final SystemInfoService systemInfoService;

    public NeisCommands(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    @Command(name = "about", description = "Show information about NEIS")
    public String about() {
        SystemInfo info = systemInfoService.getSystemInfo();
        return String.format(
                "%s v%s (%s) - running on Java %s",
                info.name(), info.version(), info.description(), info.javaVersion());
    }
}
