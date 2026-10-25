package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.shared.model.InputException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeePayrollTest {
    @Test
    void newSalaryNeedsNoHistoryAndReplacesOldAmountsWithoutDoubleCounting() throws Exception {
        var result = TestInputs.engine().calculate(TestInputs.point(payroll("CONFIRMED")));
        assertThat(result.days()).allSatisfy(day -> assertThat(day.residualOut()).isEqualByComparingTo("0"));
        assertThat(result.days().stream().map(day -> day.recurringOut()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("700.15");
        assertThat(result.days().stream().filter(day -> day.date().equals(LocalDate.of(2026, 11, 5))).findFirst())
                .hasValueSatisfying(day -> assertThat(day.recurringOut()).isEqualByComparingTo("700.15"));
    }

    @Test
    void importedUncategorizedPayrollDoesNotDoubleCountSalary() throws Exception {
        ObjectNode node = payroll("CONFIRMED");
        node.withArray("payments").forEach(payment ->
                ((ObjectNode) payment).put("categoryCode", "OTHER"));
        var result = TestInputs.engine().calculate(TestInputs.point(node));
        assertThat(result.days()).allSatisfy(day -> assertThat(day.residualOut()).isEqualByComparingTo("0"));
        assertThat(result.days().stream().map(day -> day.recurringOut()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("700.15");
    }

    @Test
    void stoppedPayrollDoesNotReturnThroughBaselineAndDoesNotSuppressOtherEmployees() throws Exception {
        ObjectNode node = payroll("EXCLUDED");
        // An unrelated employee's payments remain in the residual even though they share SALARY category.
        for (int i = 0; i < node.withArray("payments").size(); i++) {
            if (i % 7 == 0) {
                ((ObjectNode) node.withArray("payments").get(i)).put("counterpartyKey", "inn:other-employee");
            }
        }
        var result = TestInputs.engine().calculate(TestInputs.point(node));
        assertThat(result.days()).allSatisfy(day -> assertThat(day.recurringOut()).isEqualByComparingTo("0"));
        assertThat(result.days().stream().map(day -> day.residualOut()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("500.00");
    }

    @Test
    void finalKnownUnpaidSalarySurvivesStoppingTheRecurringStream() throws Exception {
        ObjectNode node = payroll("EXCLUDED");
        node.put("scope", "WITH_KNOWN_PLANS");
        var plan = (ObjectNode) TestInputs.fixture("calculate.json").withArray("knownPlans").get(0).deepCopy();
        plan.put("categoryCode", "SALARY");
        plan.put("counterpartyKey", "inn:employee");
        plan.put("recurringPatternId", node.withArray("recurringPatterns").get(0).path("id").asText());
        plan.put("relation", "REPLACES_RECURRING");
        plan.put("dueDate", "2026-11-05");
        node.withArray("knownPlans").add(plan);
        var result = TestInputs.engine().calculate(TestInputs.point(node));
        assertThat(result.days()).allSatisfy(day -> {
            assertThat(day.recurringOut()).isEqualByComparingTo("0");
            assertThat(day.residualOut()).isEqualByComparingTo("0");
        });
        assertThat(result.days().stream().map(day -> day.plannedOut()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(plan.path("remainingAmount").asText());
    }

    @Test
    void zeroObservationsAreOnlyValidForExplicitManualRulesAndOrdinaryExcludedRulesRemainForbidden() throws Exception {
        var validator = new SnapshotValidator(TestInputs.CLOCK);
        var node = payroll("CONFIRMED");
        ((ObjectNode) node.withArray("recurringPatterns").get(0)).put("detectorVersion", "DET1");
        assertThatThrownBy(() -> validator.validate(TestInputs.point(node))).isInstanceOf(InputException.class);
        var ordinaryExcluded = payroll("EXCLUDED");
        ((ObjectNode) ordinaryExcluded.withArray("recurringPatterns").get(0)).put("employeePayment", false);
        assertThatThrownBy(() -> validator.validate(TestInputs.point(ordinaryExcluded))).isInstanceOf(InputException.class);
    }

    private static ObjectNode payroll(String status) throws Exception {
        ObjectNode node = TestInputs.synthetic(LocalDate.of(2026, 10, 24), 28, "0.00", "100.00");
        node.withArray("payments").forEach(payment -> {
            ((ObjectNode) payment).put("categoryCode", "SALARY");
            ((ObjectNode) payment).put("counterpartyKey", "inn:employee");
        });
        var pattern = node.withArray("recurringPatterns").removeAll().addObject();
        pattern.put("id", UUID.randomUUID().toString());
        pattern.put("accountId", node.path("accountId").asText());
        pattern.put("direction", "OUT");
        pattern.put("categoryCode", "SALARY");
        pattern.put("counterpartyKey", "inn:employee");
        pattern.put("status", status);
        pattern.put("employeePayment", true);
        pattern.put("amount", "700.15");
        pattern.put("observations", 0);
        pattern.putNull("firstSeen");
        pattern.putNull("lastSeen");
        pattern.put("detectorVersion", "MANUAL_V1");
        pattern.put("inputDataVersion", node.path("inputDataVersion").asLong());
        pattern.put("version", 0);
        pattern.putObject("schedule").put("frequency", "MONTHLY").put("dayOfMonth", 5)
                .put("businessDayRule", "NONE").putNull("weekday");
        return node;
    }
}
