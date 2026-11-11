package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.domain.model.ContentRecord;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Driving adapter: Spring Shell commands inspecting the content tree
 * (US-11, epic #11) — what task outputs were stored, where, and for
 * which address and capture.
 */
@Component
@CommandGroup(name = "Content", description = "Content storage inspection commands (US-11)")
public class ContentCommands {

    private static final DateTimeFormatter TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ContentStoreService contentStore;

    public ContentCommands(ContentStoreService contentStore) {
        this.contentStore = contentStore;
    }

    @Command(name = {"content", "list"}, group = "Content",
            description = "List the stored content records (task outputs in the "
                    + "content tree), newest first; optionally for one address")
    public String list(
            @Option(shortName = 'a', longName = "address",
                    description = "Address id (omit for every address)") String addressId,
            @Option(longName = "limit", defaultValue = "20",
                    description = "Maximum number of records to show") int limit) {
        List<ContentRecord> all = addressId == null || addressId.isBlank()
                ? contentStore.listAll()
                : contentStore.listByAddress(addressId.trim());
        int effective = Math.max(0, limit);
        StringBuilder out = new StringBuilder("Content records (")
                .append(Math.min(effective, all.size())).append(" of ")
                .append(all.size()).append("):\n");
        all.stream().limit(effective).forEach(record -> out.append("  ")
                .append(record.taskCode()).append("  ").append(record.kind())
                .append("  ").append(record.captureId())
                .append("  -> ").append(record.path()).append("\n"));
        if (all.isEmpty()) {
            out.append("  <no content stored")
                    .append(addressId == null ? "" : " for " + addressId)
                    .append(" — run a task or chain over a verified address>\n");
        }
        return out.toString();
    }

    @Command(name = {"content", "tree"}, group = "Content",
            description = "Show the content-tree layout of one address: its capture "
                    + "folders, per-task content folders and the fetch record (US-11.4)")
    public String tree(
            @Option(shortName = 'a', longName = "address", required = true,
                    description = "Address id") String addressId) {
        List<ContentRecord> records = contentStore.listByAddress(addressId.trim());
        StringBuilder out = new StringBuilder("Content tree of ").append(addressId)
                .append(" under ").append(contentStore.root()).append(":\n");
        records.stream()
                .map(ContentRecord::path)
                .distinct()
                .sorted()
                .forEach(path -> out.append("  ").append(path).append("\n"));
        out.append("  [metadata] capture.json per capture folder (fetch record)\n");
        return out.toString();
    }
}
