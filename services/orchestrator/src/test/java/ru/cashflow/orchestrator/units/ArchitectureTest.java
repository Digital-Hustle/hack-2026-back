package ru.cashflow.orchestrator.units;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ru.cashflow.orchestrator");

    @Test
    void featuresDoNotDependOnHttpLayer() {
        noClasses().that().resideInAPackage("..features..")
                .should().dependOnClassesThat().resideInAnyPackage("..api.controller..", "..api.dto..",
                        "org.springframework.web..")
                .check(classes);
    }

    @Test
    void onlyCompanyAccessCreatesCompanyScope() {
        noClasses().that().doNotHaveFullyQualifiedName(CompanyAccess.class.getName())
                .and().doNotHaveFullyQualifiedName(CompanyScope.class.getName())
                .should().callConstructor(CompanyScope.class, java.util.UUID.class, java.util.UUID.class,
                        ru.cashflow.orchestrator.features.companies.model.Role.class)
                .check(classes);
    }

    @Test
    void moneyIsNeverFloatingPoint() {
        noFields().should().haveRawType(double.class).check(classes);
        noFields().should().haveRawType(float.class).check(classes);
        noFields().should().haveRawType(Double.class).check(classes);
        noFields().should().haveRawType(Float.class).check(classes);
    }
}
