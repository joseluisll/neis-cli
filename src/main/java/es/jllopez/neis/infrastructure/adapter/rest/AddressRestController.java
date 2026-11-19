package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.AddressCatalogService;
import es.jllopez.neis.application.AddressPreflightService;
import es.jllopez.neis.application.AddressSuggestionService;
import es.jllopez.neis.application.AddressVerificationService;
import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The address operations over REST (US-18.1, issue #116): the same
 * use cases the shell's address commands call, mapped to JSON.
 * Controllers carry no logic — parse, call, map.
 */
@RestController
@RequestMapping("/api/addresses")
@ConditionalOnServerEnabled
public class AddressRestController {

    private final AddressCatalogService catalog;
    private final AddressVerificationService verification;
    private final AddressPreflightService preflight;
    private final AddressSuggestionService suggestions;

    public AddressRestController(AddressCatalogService catalog,
                                 AddressVerificationService verification,
                                 AddressPreflightService preflight,
                                 AddressSuggestionService suggestions) {
        this.catalog = catalog;
        this.verification = verification;
        this.preflight = preflight;
        this.suggestions = suggestions;
    }

    /** Address create/edit body: id optional on create (auto ADDR-NNN);
     * every null field on edit keeps the current value. */
    public record AddressRequest(String id, String name, String description,
                                 String url, String type) {
    }

    @GetMapping
    public List<Address> list() {
        return catalog.list();
    }

    @GetMapping("/{id}")
    public Address show(@PathVariable String id) {
        return PathLookup.or404(() -> catalog.byId(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Address create(@RequestBody AddressRequest request) {
        return catalog.create(request.id(), request.name(),
                request.description(), request.url(),
                parseType(request.type()));
    }

    @PutMapping("/{id}")
    public Address update(@PathVariable String id,
                          @RequestBody AddressRequest request) {
        return PathLookup.or404(() -> catalog.update(id, request.name(),
                request.description(), request.url(),
                parseType(request.type())));
    }

    @DeleteMapping("/{id}")
    public Address remove(@PathVariable String id) {
        return PathLookup.or404(() -> catalog.remove(id));
    }

    @PostMapping("/{id}/verify")
    public es.jllopez.neis.domain.model.VerificationOutcome verify(
            @PathVariable String id) {
        return PathLookup.or404(() -> verification.verify(id));
    }

    @PostMapping("/{id}/preflight")
    public Object preflight(@PathVariable String id) {
        return PathLookup.or404(() -> preflight.preflight(id));
    }

    @PostMapping("/suggest")
    public AddressSuggestionService.Suggestion suggest(
            @RequestBody java.util.Map<String, String> body) {
        return suggestions.suggest(body.get("url"));
    }

    private AddressType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return AddressType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown address type: "
                    + type + " (URL_NEWS, URL_EVENTS, RSS_NEWS, ...)");
        }
    }
}
