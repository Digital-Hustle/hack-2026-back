package ru.cashflow.forecast.units;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void computationalFeaturesCannotReadHttpDtosOrServletRequests() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ru.cashflow.forecast");
        noClasses().that().resideInAPackage("..features..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "jakarta.servlet..", "org.springframework.web..")
                .check(classes);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "jakarta.persistence..",
                "org.springframework.data..", "org.springframework.security.oauth2..", "org.apache.kafka..", "com.rabbitmq..")
                .check(classes);
    }

    @Test
    void pointEngineHasNoAccessToCalibrationOrScoring() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ru.cashflow.forecast");
        noClasses().that().haveSimpleName("PointForecastEngine").should().dependOnClassesThat()
                .resideInAnyPackage("..risk..", "..backtest..", "..api..").check(classes);
        noClasses().that().resideInAPackage("..model..").should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "org.springframework..", "com.fasterxml.jackson..").check(classes);
    }
}
