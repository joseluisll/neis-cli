package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AddressVerificationService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driving adapter: Spring Shell commands managing the registered
 * address catalog (US-03.2, issue #19): create, list, show, edit and
 * remove addresses of both types. Thin — delegates to
 * {@link AddressCatalogService}.
 */
@Component
@CommandGroup(name = "Addresses", description = "Address catalog commands")
public class AddressCommands {

    private final AddressCatalogService catalog;
    private final AddressVerificationService verificationService;

    public AddressCommands(AddressCatalogService catalog,
                           AddressVerificationService verificationService) {
        this.catalog = catalog;
        this.verificationService = verificationService;
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
                + "\n  checked at " + outcome.checkedAt();
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
