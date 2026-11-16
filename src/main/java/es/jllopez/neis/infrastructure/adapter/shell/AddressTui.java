package es.jllopez.neis.infrastructure.adapter.shell;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;

/**
 * The address-management TUI loop (US-09.1, issue #37, user story #9):
 * a menu-driven screen over the address catalog — list, create, edit
 * and remove — redrawn from live state after every operation, so the
 * verification status and the classified webpage type shown are never
 * stale. Thin over {@link AddressCatalogService}: the catalog keeps
 * its own rules (URL validation, idempotent re-registration, edits
 * never touching verification state or capabilities).
 *
 * <p>Interaction rules: EMPTY input on an add/edit field keeps the
 * default (auto id / current value / none); an invalid value aborts
 * THAT operation with a note — the screen is redrawn, the catalog is
 * never left half-updated; EOF exits cleanly (piped input, Ctrl-D).
 * The confirmations are explicit: a removal needs a separate y.</p>
 */
class AddressTui {

    private final AddressCatalogService catalog;
    private final ConsoleIO io;
    /** True when re-entered from the management screen (US-16.1): quit
     * then means 'back to the management menu' and must NOT print
     * 'Bye.' — that would claim an exit that did not happen (US-16.1
     * review finding #97). The standalone 'address tui' command keeps
     * the exit message. */
    private final boolean nested;

    AddressTui(AddressCatalogService catalog, ConsoleIO io) {
        this(catalog, io, false);
    }

    AddressTui(AddressCatalogService catalog, ConsoleIO io, boolean nested) {
        this.catalog = catalog;
        this.io = io;
        this.nested = nested;
    }

    /** Runs the screen loop until the user quits or input ends. */
    void run() {
        io.println("Address management (US-09.1) — the screen redraws from " +
                "live state after every operation.");
        while (true) {
            ConsoleIO.printScreen(io, ConsoleIO.addressTable(catalog.list()));
            String line = io.readLine("address> ");
            if (line == null) {
                io.println("(input ended)");
                return;
            }
            switch (line.trim().toLowerCase()) {
                case "" -> { /* redraw */ }
                case "l", "list" -> { /* the redraw IS the list */ }
                case "a", "add" -> add();
                case "e", "edit" -> edit();
                case "r", "remove" -> remove();
                case "q", "quit", "exit" -> {
                    if (!nested) {
                        io.println("Bye.");
                    }
                    return;
                }
                default -> io.println("Unknown command '" + line.trim()
                        + "' — choose one of l/a/e/r/q.");
            }
        }
    }

    private void add() {
        String id = io.readLine("  id (empty = next free ADDR-NNN): ");
        if (id == null) {
            return;
        }
        // An EXPLICIT id that already exists is refused here (US-09.1
        // review finding: create() re-registers idempotently — the
        // documented US-15.4 behavior the seeders rely on — so the TUI
        // used to silently overwrite the existing record). Editing is
        // the explicit path for changing an address.
        String requestedId = id.trim();
        if (!requestedId.isEmpty()) {
            try {
                catalog.byId(requestedId);
                io.println("  Refused: " + requestedId
                        + " already exists — edit it instead ('e'); nothing was created.");
                return;
            } catch (IllegalArgumentException unknown) {
                // a free id — proceed
            }
        }
        String name = io.readLine("  name: ");
        if (name == null) {
            return;
        }
        String description = io.readLine("  description (empty = none): ");
        if (description == null) {
            return;
        }
        String url = io.readLine("  url: ");
        if (url == null) {
            return;
        }
        String type = io.readLine("  lists [NEWS|EVENTS]: ");
        if (type == null) {
            return;
        }
        AddressType addressType = parseType(type);
        if (addressType == null) {
            io.println("  Refused: the listing kind must be NEWS or EVENTS (got '"
                    + type.trim() + "') — nothing was created.");
            return;
        }
        try {
            Address created = catalog.create(
                    requestedId.isEmpty() ? null : requestedId,
                    name.isBlank() ? "(unnamed)" : name.trim(),
                    description.isBlank() ? null : description.trim(),
                    url.trim(),
                    addressType);
            io.println("  Registered " + created.id() + " -> " + created.url());
        } catch (IllegalArgumentException | IllegalStateException e) {
            io.println("  Refused: " + e.getMessage() + " — nothing was created.");
        }
    }

    private void edit() {
        String id = io.readLine("  id of the address to edit: ");
        if (id == null) {
            return;
        }
        Address current;
        try {
            current = catalog.byId(id.trim());
        } catch (IllegalArgumentException e) {
            io.println("  " + e.getMessage());
            return;
        }
        io.println("  Editing " + current.id() + " — EMPTY input keeps the current value.");
        String name = io.readLine("  name [" + current.name() + "]: ");
        if (name == null) {
            return;
        }
        String description = io.readLine("  description ["
                + (current.description() == null ? "(none)" : current.description()) + "]: ");
        if (description == null) {
            return;
        }
        String url = io.readLine("  url [" + current.url() + "]: ");
        if (url == null) {
            return;
        }
        String type = io.readLine("  lists [" + current.itemKind().toUpperCase() + "]: ");
        if (type == null) {
            return;
        }
        AddressType addressType = type.isBlank()
                ? current.type() : parseType(type);
        if (addressType == null) {
            io.println("  Refused: the listing kind must be NEWS or EVENTS (got '"
                    + type.trim() + "') — nothing was changed.");
            return;
        }
        try {
            Address updated = catalog.update(current.id(),
                    name.isBlank() ? null : name.trim(),
                    description.isBlank() ? null : description.trim(),
                    url.isBlank() ? null : url.trim(),
                    addressType);
            io.println("  Updated " + updated.id() + " (verification state kept: "
                    + updated.verificationStatus() + ").");
        } catch (IllegalArgumentException | IllegalStateException e) {
            io.println("  Refused: " + e.getMessage() + " — nothing was changed.");
        }
    }

    private void remove() {
        String id = io.readLine("  id of the address to remove: ");
        if (id == null) {
            return;
        }
        // The id is resolved BEFORE the confirmation (US-09.1 review
        // finding: the old flow asked the user to confirm a removal
        // that could not happen — the id was unknown).
        Address existing;
        try {
            existing = catalog.byId(id.trim());
        } catch (IllegalArgumentException e) {
            io.println("  " + e.getMessage());
            return;
        }
        String confirm = io.readLine("  Remove " + existing.id() + " ("
                + existing.name() + ")? [y/N]: ");
        if (confirm == null) {
            return;
        }
        if (!confirm.trim().equalsIgnoreCase("y")) {
            io.println("  Cancelled — nothing was removed.");
            return;
        }
        try {
            Address removed = catalog.remove(existing.id());
            io.println("  Removed " + removed.id() + " (" + removed.name() + ").");
        } catch (IllegalArgumentException | IllegalStateException e) {
            io.println("  Refused: " + e.getMessage());
        }
    }

    /** NEWS/EVENTS (case-insensitive); null when neither. */
    private static AddressType parseType(String type) {
        return switch (type.trim().toUpperCase()) {
            case "NEWS" -> AddressType.URL_NEWS;
            case "EVENTS" -> AddressType.URL_EVENTS;
            default -> null;
        };
    }
}
