package es.jllopez.neis.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Syntactic URL validation (US-04.1, issue #21): every address must
 * carry a well-formed http(s) URL at creation and before use.
 */
class UrlSyntaxValidatorTest {

    private final UrlSyntaxValidator validator = new UrlSyntaxValidator();

    @Test
    void acceptsWellFormedHttpAndHttpsUrls() {
        assertTrue(validator.isValid("https://ec.europa.eu/news"));
        assertTrue(validator.isValid("http://example.org/list?page=2"));
        assertTrue(validator
                .isValid("https://hadea.ec.europa.eu/events_en?query=a%20b#anchor"));
    }

    @Test
    void rejectsEmptyAndUnparseableUrls() {
        assertTrue(validator.validate(null).isPresent());
        assertTrue(validator.validate("   ").isPresent());
        assertTrue(validator.validate("not a url").isPresent(),
                "unescaped spaces are not a valid URI");
    }

    @Test
    void rejectsMissingOrUnsupportedSchemes() {
        assertEquals(
                Optional.of("the URL has no scheme (expected http or https): example.org/news"),
                validator.validate("example.org/news"));
        assertTrue(validator.validate("ftp://example.org/files").isPresent());
    }

    @Test
    void rejectsMissingHost() {
        assertTrue(validator.validate("https://").isPresent());
        assertTrue(validator.validate("https:///path-only").isPresent());
    }

    @Test
    void rejectionReasonsAreHumanReadable() {
        Optional<String> reason = validator.validate("mailto:someone@example.org");
        assertFalse(reason.orElseThrow().isEmpty());
        assertFalse(reason.orElseThrow().endsWith("null"));
    }
}
