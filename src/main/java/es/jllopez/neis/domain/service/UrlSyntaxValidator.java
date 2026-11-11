package es.jllopez.neis.domain.service;

import java.net.URI;
import java.util.Optional;

/**
 * Syntactic URL validation for registered addresses (US-04.1, issue
 * #21): every address is validated at creation/edit time and again
 * before it is used by an executor. Pure Java, framework-free.
 *
 * <p>This checks SYNTAX only. Reachability (US-04.2) is a separate
 * verification process; eligibility (US-04.3) requires both.</p>
 */
public class UrlSyntaxValidator {

    /**
     * Returns an empty optional when the URL is syntactically valid,
     * or a human-readable reason when it is not.
     */
    public Optional<String> validate(String url) {
        if (url == null || url.isBlank()) {
            return Optional.of("the URL is empty");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return Optional.of("the URL is not a valid URI: " + e.getMessage());
        }
        if (uri.getScheme() == null) {
            return Optional.of("the URL has no scheme (expected http or https): " + url);
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("the URL scheme must be http or https, was: " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.of("the URL has no host: " + url);
        }
        return Optional.empty();
    }

    /** True when the URL passes syntactic validation. */
    public boolean isValid(String url) {
        return validate(url).isEmpty();
    }
}
