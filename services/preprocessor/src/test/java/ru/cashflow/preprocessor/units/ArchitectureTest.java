package ru.cashflow.preprocessor.units;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ru.cashflow.preprocessor");

    @Test
    void parsingAndCategorizationDoNotDependOnHttpDtoOrPersistence() {
        noClasses().that().resideInAPackage("..features..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "org.springframework.web..", "java.sql..",
                        "jakarta.persistence..", "org.springframework.data..").check(classes);
    }

    @Test
    void financialModelHasNoFloatingPointFields() {
        noFields().that().areDeclaredInClassesThat().resideInAPackage("..model..")
                .should().haveRawType(double.class).check(classes);
        noFields().that().areDeclaredInClassesThat().resideInAPackage("..model..")
                .should().haveRawType(float.class).check(classes);
    }
}
