package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AddressPreflightService;
import es.jllopez.neis.application.AddressSuggestionService;
import es.jllopez.neis.application.AddressVerificationService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.port.WebpageTypeCatalogPort;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driving adapter: Spring Shell commands managing the registered
 * address catalog (US-03.2, issue #19): create, list, show, edit and
 * remove addresses of both types. Thin — delegates to
 * {@link AddressCatalogService}. {@code address tui} (US-09.1,
 * issue #37) opens the interactive address-management screen
 * ({@link AddressTui}: list/add/edit/remove on the shell's own JLine
 * terminal, redrawn from live state with the verification status and
 * the classified webpage type, empty-input-keeps semantics, loud
 * refusals, explicit removal confirmation, clean EOF exit).
 */
@Component
@CommandGroup(name = "Addresses", description = "Address catalog commands")
public class AddressCommands {

    private final AddressCatalogService catalog;
    private final AddressVerificationService verificationService;
    private final WebpageTypeCatalogPort webpageTypes;
    private final AddressSuggestionService suggestions;
    private final AddressPreflightService preflightService;

    public AddressCommands(AddressCatalogService catalog,
                           AddressVerificationService verificationService,
                           WebpageTypeCatalogPort webpageTypes,
                           AddressSuggestionService suggestions,
                           AddressPreflightService preflightService) {
        this.catalog = catalog;
        this.verificationService = verificationService;
        this.webpageTypes = webpageTypes;
        this.suggestions = suggestions;
        this.preflightService = preflightService;
    }

    @Command(name = {"address", "add"}, group = "Addresses",
            description = "Register a new address (URL_NEWS or URL_EVENTS); "
                    + "the id is auto-assigned when omitted")
    public String add(
            @Option(shortName = 'n', longName = "name", required = true,
                    description = "Short name of the address") String name,
            @Option(shortName = 'u', longName = "url", required = true,
                    description = "Listing URL") String url,
            @Option(shortName = 't', longName = "type", required = true,
                    description = "Listing kind: URL_NEWS or URL_EVENTS") String type,
            @Option(shortName = 'd', longName = "description",
                    description = "Free text description of the source") String description,
            @Option(shortName = 'i', longName = "id",
                    description = "Address id (ADDR-NNN); auto-assigned when omitted") String id) {
        Address address = catalog.create(id, name, description, url, parseType(type));
        return "Registered " + address.id() + " [" + address.itemKind() + "] "
                + address.name() + "\n        " + address.url();
    }

    @Command(name = {"address", "list"}, group = "Addresses",
            description = "List the registered addresses")
    public String list(
            @Option(shortName = 't', longName = "type",
                    description = "Filter by listing kind: URL_NEWS or URL_EVENTS") String type) {
        List<Address> addresses = catalog.list();
        AddressType filter = parseType(type); // null when not given
        StringBuilder out = new StringBuilder(
                "Registered addresses" + (filter != null ? " [" + filter + "]" : "")
                        + " (" + addresses.size() + "):\n");
        for (Address address : addresses) {
            if (filter != null && address.type() != filter) {
                continue;
            }
            out.append("  ").append(address.id())
                    .append("  [").append(address.itemKind()).append("]  ")
                    .append(address.name()).append("\n")
                    .append("        ").append(address.url()).append("\n");
        }
        return out.toString();
    }

    @Command(name = {"address", "show"}, group = "Addresses",
            description = "Show one registered address with all its fields")
    public String show(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Address id") String id) {
        return render(catalog.byId(id));
    }

    @Command(name = {"address", "edit"}, group = "Addresses",
            description = "Edit an address (every option omitted keeps its value)")
    public String edit(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Address id") String id,
            @Option(shortName = 'n', longName = "name",
                    description = "New name") String name,
            @Option(shortName = 'u', longName = "url",
                    description = "New listing URL") String url,
            @Option(shortName = 't', longName = "type",
                    description = "New listing kind: URL_NEWS or URL_EVENTS") String type,
            @Option(shortName = 'd', longName = "description",
                    description = "New description") String description) {
        Address address = catalog.update(id, name, description, url, parseType(type));
        return "Updated " + address.id() + "\n" + render(address);
    }

    @Command(name = {"address", "remove"}, group = "Addresses",
            description = "Remove an address from the catalog (its stored "
                    + "working selector goes with it; execution/content history is kept)")
    public String remove(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Address id") String id) {
        Address removed = catalog.remove(id);
        return "Removed " + removed.id() + " (" + removed.name() + ")";
    }

    @Command(name = {"address", "verify"}, group = "Addresses",
            description = "Verify an address is reachable (US-04.2) and record "
                    + "the outcome on it; with --all, verifies every registered address")
    public String verify(
            @Option(shortName = 'i', longName = "id",
                    description = "Address id") String id,
            @Option(longName = "all", description = "Verify every registered address",
                    defaultValue = "false") boolean all) {
        if (all) {
            List<String> lines = verificationService.verifyAll();
            StringBuilder out = new StringBuilder("Verification results:\n");
            lines.forEach(line -> out.append("  ").append(line).append("\n"));
            return out.toString();
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Give an address id or --all");
        }
        var outcome = verificationService.verify(id);
        return id + " -> " + outcome.status()
                + (outcome.statusCode() > 0 ? " (HTTP " + outcome.statusCode() + ")" : "")
                + "\n  " + outcome.detail()
                + "\n  checked at " + outcome.checkedAt()
                + (outcome.webpageType() == null ? ""
                        : "\n  webpage type: " + outcome.webpageType());
    }

    @Command(name = {"address", "webpage-type"}, group = "Addresses",
            description = "Set or clear the address's webpage type (US-06.5): the "
                    + "taxonomy id deciding the retrieval strategy (static, spa, "
                    + "spa-angular, spa-react, spa-vue, hybrid, other — configurable "
                    + "extensions too); the per-run connector parameter overrides it. "
                    + "Without --type, prints the stored type.")
    public String webpageType(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Address id") String id,
            @Option(longName = "type",
                    description = "Taxonomy id (omit to show; 'clear' removes)") String type) {
        if (type == null || type.isBlank()) {
            String stored = catalog.byId(id).webpageType();
            return id + " webpage type: " + (stored == null ? "<unclassified>"
                    : stored + " (strategy " + webpageTypes.byId(stored)
                            .map(t -> t.strategy()).orElse("?") + ")");
        }
        if (type.trim().equalsIgnoreCase("clear")) {
            catalog.updateWebpageType(id, null);
            return id + " webpage type cleared (unclassified; live runs fall "
                    + "back to HTTP until the next verification classifies it)";
        }
        if (!webpageTypes.isKnown(type)) {
            throw new IllegalArgumentException("Unknown webpage type '" + type
                    + "' — known types: " + webpageTypes.all().stream()
                            .map(t -> t.id()).sorted().toList());
        }
        Address updated = catalog.updateWebpageType(id, type.trim());
        return id + " webpage type: " + updated.webpageType()
                + " (strategy " + webpageTypes.byId(updated.webpageType())
                        .map(t -> t.strategy()).orElse("?") + ")";
    }

    @Command(name = {"address", "suggest"}, group = "Addresses",
            description = "AI-assisted registration (US-13.5): given only a URL, the AI "
                    + "proposes name/type/description/metadata from the page; you confirm, "
                    + "edit or reject before anything is saved")
    public String suggest(
            @Option(shortName = 'u', longName = "url", required = true,
                    description = "Listing URL to propose the address from") String url) {
        AddressSuggestionService.Suggestion s = suggestions.suggest(url.trim());
        StringBuilder out = new StringBuilder("AI proposal for ").append(s.url())
                .append(":\n");
        out.append("  name        : ")
                .append(s.name() == null ? "(none proposed)" : s.name()).append("\n");
        out.append("  type        : ")
                .append(s.type() == null ? "(unrecognized — NEWS or EVENTS required)"
                        : s.type()).append("\n");
        out.append("  description : ")
                .append(s.description() == null ? "(none proposed)" : s.description())
                .append("\n");
        if (!s.metadata().isEmpty()) {
            out.append("  metadata    :\n");
            s.metadata().forEach((k, v) -> out.append("    ").append(k)
                    .append(": ").append(v).append("\n"));
        }
        out.append("\nNothing was saved. Register it with:\n");
        out.append("  address add -n '<name>' -u '").append(s.url())
                .append("' -t ").append(s.type() == null ? "URL_NEWS|URL_EVENTS"
                        : s.type());
        if (s.description() != null && !s.description().isBlank()) {
            out.append(" -d '<description>'");
        }
        out.append("\n(edit the proposed values as you confirm them)");
        return out.toString();
    }

    @Command(name = {"address", "preflight"}, group = "Addresses",
            description = "Preflight capture (US-13.6): lightweight entry-page probe "
                    + "(static fetch, rendered probe on an application shell) that "
                    + "classifies the address into the webpage-type taxonomy and "
                    + "estimates the retrieval connector; both persisted as metadata "
                    + "(overridable)")
    public String preflight(
            @Option(shortName = 'i', longName = "id", required = true,
                    description = "Address id") String id) {
        var outcome = preflightService.preflight(id);
        Address updated = catalog.byId(id);
        StringBuilder out = new StringBuilder("Preflight of ").append(id).append(":\n");
        out.append("  ").append(outcome.evidence()).append("\n");
        out.append("  webpage type: ")
                .append(updated.webpageType() == null ? "<unclassified>"
                        : updated.webpageType() + " (strategy "
                        + webpageTypes.byId(updated.webpageType())
                                .map(Object::toString).orElse("?") + ")")
                .append("\n");
        out.append("  estimated connector: ").append(outcome.estimatedConnector() == null
                ? "(unknown — the per-run connector parameter decides)" : outcome.estimatedConnector())
                .append(" (suggestion — override with 'address webpage-type' or the per-run connector parameter)\n");
        return out.toString();
    }

    private AddressType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return AddressType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown address type: " + type + " (expected URL_NEWS or URL_EVENTS)");
        }
    }

    private String render(Address a) {
        StringBuilder out = new StringBuilder();
        out.append(a.id()).append("  [").append(a.itemKind()).append("]  ")
                .append(a.name()).append("\n");
        out.append("  url         : ").append(a.url()).append("\n");
        out.append("  description : ")
                .append(a.description() == null || a.description().isBlank()
                        ? "-" : a.description()).append("\n");
        out.append("  verification: ").append(a.verificationStatus())
                .append(a.verifiedAt() == null ? "" : " (checked at " + a.verifiedAt() + ")")
                .append("\n");
        return out.toString();
    }
}
