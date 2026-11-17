package es.jllopez.neis.application;

import es.jllopez.neis.testsupport.InMemorySettingsStorePort;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editable-configuration use case (US-16.5 edit path, issue #97
 * review finding): validation BEFORE save, the allowlist, the clear
 * path and the effective view.
 */
class SettingsServiceTest {

    private final InMemorySettingsStorePort store = new InMemorySettingsStorePort();
    private final SettingsService settings = new SettingsService(store,
            new org.springframework.core.env.StandardEnvironment());

    @Test
    void validValuesAreStoredAndVisibleAsOverrides() {
        settings.set(SettingsService.SCRAPE_CONNECTOR, " live ");
        assertEquals(Optional.of("live"), store.find(SettingsService.SCRAPE_CONNECTOR),
                "the stored form is the normalized one");
        assertEquals(Optional.of("live"), settings.overrideOf(SettingsService.SCRAPE_CONNECTOR));

        settings.set(SettingsService.EXTRACTION_MAX_ITEMS, "500");
        assertEquals(Optional.of("500"), store.find(SettingsService.EXTRACTION_MAX_ITEMS));

        settings.set(SettingsService.VERIFICATION_TIMEOUT, "30");
        assertEquals(Optional.of("30"), store.find(SettingsService.VERIFICATION_TIMEOUT));
    }

    @Test
    void invalidValuesAreRefusedBeforeAnythingIsWritten() {
        // Typo'd values are refused, nothing partial is stored.
        assertThrows(IllegalArgumentException.class,
                () -> settings.set(SettingsService.SCRAPE_CONNECTOR, "bogus"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.set(SettingsService.EXTRACTION_MAX_ITEMS, "0"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.set(SettingsService.EXTRACTION_MAX_ITEMS, "-3"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.set(SettingsService.EXTRACTION_MAX_ITEMS, "many"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.set(SettingsService.VERIFICATION_TIMEOUT, "0"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.set(SettingsService.VERIFICATION_TIMEOUT, "4000"));
        assertTrue(store.all().isEmpty(), "a refused edit must not write anything");
    }

    @Test
    void nonEditableKeysAreRefused() {
        assertFalse(settings.isEditable("neis.db.path"));
        assertFalse(settings.isEditable("neis.ai.enabled"));
        assertFalse(settings.isEditable(null));
        var refusal = settings.validate("neis.db.path", "other.db");
        assertTrue(refusal.isPresent(), "the boot-critical path is not editable");
        assertThrows(IllegalArgumentException.class,
                () -> settings.set("neis.db.path", "other.db"));
    }

    @Test
    void anEmptyValueClearsTheStoredOverride() {
        settings.set(SettingsService.SCRAPE_CONNECTOR, "live");
        assertEquals(Optional.of("live"), store.find(SettingsService.SCRAPE_CONNECTOR));

        settings.set(SettingsService.SCRAPE_CONNECTOR, "  ");
        assertTrue(store.find(SettingsService.SCRAPE_CONNECTOR).isEmpty(),
                "the empty value clears the override");
        assertTrue(settings.overrideOf(SettingsService.SCRAPE_CONNECTOR).isEmpty());
    }

    @Test
    void effectiveLayersTheOverrideOverTheBootValue() {
        System.setProperty(SettingsService.VERIFICATION_TIMEOUT, "12");
        try {
            assertEquals("12", settings.effective(SettingsService.VERIFICATION_TIMEOUT),
                    "no override: the boot value shows");
            settings.set(SettingsService.VERIFICATION_TIMEOUT, "45");
            assertEquals("45", settings.effective(SettingsService.VERIFICATION_TIMEOUT),
                    "the override wins over the boot value");
        } finally {
            System.clearProperty(SettingsService.VERIFICATION_TIMEOUT);
        }
    }

    @Test
    void effectiveReadsYamlOnlyValuesFromTheLiveEnvironment() {
        // US-16.7 (issue #103): application.yml values are NOT mirrored
        // into system properties — the effective view must resolve them
        // from the Spring Environment, or a YAML-configured value
        // displayed as unset although it IS configured and effective.
        org.springframework.mock.env.MockEnvironment env =
                new org.springframework.mock.env.MockEnvironment();
        env.setProperty(SettingsService.SCRAPE_CONNECTOR, "static");
        env.setProperty(SettingsService.VERIFICATION_TIMEOUT, "10");
        SettingsService service = new SettingsService(store, env);

        assertEquals("static", service.effective(SettingsService.SCRAPE_CONNECTOR),
                "the YAML-configured value is effective, not unset");
        assertEquals("10", service.effective(SettingsService.VERIFICATION_TIMEOUT),
                "the YAML-configured timeout shows");

        // A stored override still wins over the Environment.
        service.set(SettingsService.VERIFICATION_TIMEOUT, "30");
        assertEquals("30", service.effective(SettingsService.VERIFICATION_TIMEOUT),
                "the override layers over the Environment value");
    }
}
