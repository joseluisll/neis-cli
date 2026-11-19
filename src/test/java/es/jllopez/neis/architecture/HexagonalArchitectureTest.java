package es.jllopez.neis.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
    /**
     * US-18 (issues #116/#118): REST, MCP and shell are SIBLING driving
     * adapters over the same application use cases — none may depend on
     * another (no controller calling a controller, no MCP tool reaching
     * into the REST layer; the shared server-mode gate annotation lives
     * in the neutral adapter package above all three).
     */
    @Test
    void drivingAdaptersMustNotDependOnEachOther() {
        String[][] groups = {
                {"..adapter.rest..", "..adapter.mcp..", "..adapter.shell.."},
                {"..adapter.mcp..", "..adapter.rest..", "..adapter.shell.."},
                {"..adapter.shell..", "..adapter.rest..", "..adapter.mcp.."}};
        for (String[] g : groups) {
            ArchRule rule = noClasses().that().resideInAPackage(g[0])
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(g[1], g[2]);
            rule.check(importedClasses);
        }
    }

    /**
     * US-18.3 review finding: the MCP adapter's contract is "delegate
     * to application use cases" — it may depend on application/domain
     * and its own shared adapter plumbing, but NEVER on other
     * infrastructure internals (persistence, connectors, other
     * adapters). Enforced positively so a future tool cannot silently
     * reach into e.g. a repository.
     *
     * <p>US-15.6 review note (N2): classes in the ROOT package
     * ({@code es.jllopez.neis}, e.g. {@link ServerModeConfig},
     * {@link McpSessionsShutdownLifecycle},
     * {@link TerminationSignalHandler}) sit OUTSIDE every adapter
     * package rule — that is BY DESIGN: the root is the neutral
     * server-mode WIRING zone (mode gates, lifecycle hooks, unit-file
     * semantics), not a driving adapter, so the delegation rules do
     * not apply. It must not become a bypass lane for adapter logic:
     * anything that delegates to a use case belongs in an adapter
     * package and stays under its rule.</p>
     */
    @Test
    void mcpAdapterDelegatesOnlyToApplicationAndDomain() {
        ArchRule rule = classes().that().resideInAPackage("..adapter.mcp..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "..application..",
                        "..domain..",
                        "es.jllopez.neis.infrastructure.adapter..",
                        "org.springframework..",
                        "org.slf4j..",
                        "java..");
        rule.check(importedClasses);
    }

    /**
     * US-18 (owner decision 2026-11-19): NO AI functionality over MCP.
     * The mcp adapter may use Spring AI's MCP annotations, but never
     * the AI use case or its port (the rule is positive on everything
     * EXCEPT the AI integration points, so a future tool cannot
     * silently grow an AI dependency).
     */
    @Test
    void mcpAdapterMustNotReachTheAiIntegrationPoint() {
        // resideInAnyPackage matches PACKAGE names only — the first cut
        // of this rule named CLASSES and could never fail (US-18.4
        // re-review). Fully-qualified names are the class-level pin.
        ArchRule rule = noClasses().that().resideInAPackage("..adapter.mcp..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "es.jllopez.neis.application.AiAssistantService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "es.jllopez.neis.domain.port.AiAssistantPort");
        rule.check(importedClasses);
    }

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
