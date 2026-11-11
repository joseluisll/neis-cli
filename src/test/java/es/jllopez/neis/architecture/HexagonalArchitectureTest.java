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
}
