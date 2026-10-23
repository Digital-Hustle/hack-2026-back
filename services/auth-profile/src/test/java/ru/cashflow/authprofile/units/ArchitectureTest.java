package ru.cashflow.authprofile.units;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("ru.cashflow.authprofile");

    @Test
    void businessLayersDoNotDependOnHttpOrJdbcTemplate() {
        noClasses()
                .that()
                .resideInAPackage("..features..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "ru.cashflow.authprofile.api..",
                        "jakarta.servlet..",
                        "org.springframework.http..",
                        "org.springframework.jdbc.core..")
                .check(classes);
    }

    @Test
    void orchestrationDoesNotAccessPersistence() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..repository..", "org.jooq..")
                .check(classes);
    }

    @Test
    void sqlIsConfinedToRepositories() {
        noClasses()
                .that()
                .resideInAPackage("..features..")
                .and()
                .resideOutsideOfPackage("..repository..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.jooq..", "java.sql..", "javax.sql..")
                .check(classes);
    }

    @Test
    void controllersDoNotAccessPersistence() {
        noClasses()
                .that()
                .resideInAPackage("..api.controller..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..repository..", "org.jooq..", "org.springframework.mail..")
                .check(classes);
    }

    @Test
    void mailClientRemainsInsideGateway() {
        noClasses()
                .that()
                .resideInAPackage("..features..")
                .and()
                .resideOutsideOfPackage("..gateway..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework.mail..")
                .check(classes);
    }

    @Test
    void queriesNeverDependOnCommands() {
        noClasses()
                .that()
                .resideInAPackage("..query..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..command..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void crossFeatureDependenciesUsePublicApis() {
        noClasses()
                .that()
                .resideInAPackage("..features.security..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..features.profile.application..",
                        "..features.profile.command..",
                        "..features.profile.query..",
                        "..features.profile.model..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..features.profile..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..features.security.application..",
                        "..features.security.command..",
                        "..features.security.query..",
                        "..features.security.model..")
                .check(classes);
        slices().matching("ru.cashflow.authprofile.features.(*)..")
                .should()
                .beFreeOfCycles()
                .check(classes);
    }

    @Test
    void businessTimeGoesThroughApplicationClock() {
        noClasses()
                .that()
                .resideInAPackage("..features..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.time.Clock")
                .check(classes);
    }
}
