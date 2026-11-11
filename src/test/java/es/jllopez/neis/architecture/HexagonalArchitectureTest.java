package es.jllopez.neis.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal layering rules (issue #13): dependencies point inwards only.
 */
class HexagonalArchitectureTest {

    private static final String BASE_PACKAGE = "es.jllopez.neis";

    private final JavaClasses importedClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(BASE_PACKAGE);

    @Test
    void domainMustNotDependOnApplicationOrInfrastructure() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..");
        rule.check(importedClasses);
    }

    @Test
    void applicationMustNotDependOnInfrastructure() {
        ArchRule rule = noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..infrastructure..");
        rule.check(importedClasses);
    }

    @Test
    void domainMustBeFrameworkFree() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..");
        rule.check(importedClasses);
    }

    /**
     * US-01.4 (issue #63): the owner-confirmed open-source building
     * blocks are implementation details behind the ports. Domain and
     * application may never import them — swap a component without
     * touching the inner layers.
     */
    @Test
    void domainAndApplicationMustNotDependOnStackLibraries() {
        ArchRule rule = noClasses().that()
                .resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.jsoup..",               // HTML5 parsing / selectors
                        "org.apache.hc..",           // Apache HttpClient 5
                        "com.microsoft.playwright..", // JS rendering
                        "org.apache.tika..",         // content detection
                        "jakarta.persistence..",     // JPA
                        "org.hibernate..",           // Hibernate ORM
                        "org.springframework.data..", // Spring Data repositories
                        "org.springframework.ai..",  // Spring AI
                        "com.rometools..",           // Rome feeds
                        "net.dankito.readability4j..", // Readability4J
                        "org.sqlite..");             // sqlite-jdbc
        rule.check(importedClasses);
    }
}
