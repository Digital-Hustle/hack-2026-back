package ru.cashflow.forecast.units;

import java.time.LocalDate;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.HistoryPreparer;
import ru.cashflow.forecast.features.forecast.application.PlanResolver;
import ru.cashflow.forecast.features.forecast.application.RecurringCalendar;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.model.ResolvedCalendar;
import ru.cashflow.forecast.features.forecast.model.StreamKey;
import ru.cashflow.forecast.features.shared.model.BusinessDayRule;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.InputException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanResolverTest {
    private final RecurringCalendar calendar = new RecurringCalendar();
    private final PlanResolver resolver = new PlanResolver(calendar);

    @Test
    void additionalPlanUsesOnlyUnpaidRemainderAndOverdueIsNotMovedToTomorrow() throws Exception {
        ObjectNode node = TestInputs.fixture("calculate.json");
        ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
        plan.put("settledAmount", "60000.00");
        plan.put("remainingAmount", "100000.00");
        plan.put("status", "PARTIALLY_SETTLED");
        assertThat(resolve(node).planned().get(LocalDate.of(2026, 10, 29)).outflow()).isEqualTo("100000.00");
        plan.put("dueDate", "2026-10-23");
        plan.put("overdue", true);
        var overdue = resolve(node);
        assertThat(overdue.planned()).isEmpty();
        assertThat(overdue.warnings()).extracting(warning -> warning.code()).contains("UNMATCHED_PLAN");
    }

    @Test
    void replacesOneRecurringInstanceNotEntireSchedule() throws Exception {
        ObjectNode node = withPattern();
        ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
        plan.put("relation", "REPLACES_RECURRING");
        plan.put("recurringPatternId", "66666666-6666-4666-8666-666666666666");
        plan.put("counterpartyKey", "landlord");
        plan.put("categoryCode", "RENT");
        plan.put("dueDate", "2026-10-26");
        var result = resolve(node);
        assertThat(result.recurring()).doesNotContainKey(LocalDate.of(2026, 10, 26));
        assertThat(result.recurring().get(LocalDate.of(2026, 11, 2)).outflow()).isEqualTo("100.00");
        assertThat(result.planned().get(LocalDate.of(2026, 10, 26)).outflow()).isEqualTo("160000.00");
        var second = plan.deepCopy();
        second.put("id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        node.withArray("knownPlans").add(second);
        assertThatThrownBy(() -> resolve(node)).isInstanceOf(InputException.class);
    }

    @Test
    void replacementRequiresActualLinkedResidualStreamAndCorrectRecurringDirection() throws Exception {
        ObjectNode node = TestInputs.fixture("calculate.json");
        ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
        plan.put("relation", "REPLACES_BASELINE");
        plan.put("baselineCounterpartyKey", "supplier-a");
        assertThat(resolve(node).baselineReplacements()).containsExactly(new StreamKey(Direction.OUT, "supplier-a"));
        plan.put("baselineCounterpartyKey", "unknown");
        assertThatThrownBy(() -> resolve(node)).isInstanceOf(InputException.class);
        ObjectNode recurring = withPattern();
        ObjectNode recurringPlan = (ObjectNode) recurring.withArray("knownPlans").get(0);
        recurringPlan.put("relation", "REPLACES_RECURRING");
        recurringPlan.put("recurringPatternId", "66666666-6666-4666-8666-666666666666");
        recurringPlan.put("dueDate", "2026-10-26");
        recurringPlan.put("direction", "IN");
        assertThatThrownBy(() -> resolve(recurring)).isInstanceOf(InputException.class);
    }

    @Test
    void nominalWeekendReplacementRemovesShiftedInstanceButKeepsExplicitPlanDate() throws Exception {
        ObjectNode node = TestInputs.fixture("calculate.json");
        node.withArray("recurringPatterns").add(TestInputs.JSON.valueToTree(
                RecurringCalendarTest.pattern(Frequency.WEEKLY, 7, null, BusinessDayRule.FOLLOWING)));
        ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
        plan.put("relation", "REPLACES_RECURRING");
        plan.put("recurringPatternId", "66666666-6666-4666-8666-666666666666");
        plan.put("counterpartyKey", "landlord");
        plan.put("categoryCode", "RENT");
        plan.put("dueDate", "2026-10-25");
        var result = resolve(node);
        assertThat(result.planned()).containsKey(LocalDate.of(2026, 10, 25)).doesNotContainKey(LocalDate.of(2026, 10, 26));
        assertThat(result.recurring()).doesNotContainKey(LocalDate.of(2026, 10, 26));
        plan.put("counterpartyKey", "wrong-party");
        assertThatThrownBy(() -> resolve(node)).isInstanceOf(InputException.class);
    }

    private ObjectNode withPattern() throws Exception {
        ObjectNode node = TestInputs.fixture("calculate.json");
        node.withArray("recurringPatterns").add(TestInputs.JSON.valueToTree(
                RecurringCalendarTest.pattern(Frequency.WEEKLY, 1, null, BusinessDayRule.NONE)));
        return node;
    }

    private ResolvedCalendar resolve(ObjectNode node) throws Exception {
        var input = TestInputs.point(node);
        var history = new HistoryPreparer().prepare(input);
        var residuals = new ResidualExtractor(calendar).extract(input, history.days(), history.payments(), Set.of(), false);
        return resolver.resolve(input, history, residuals);
    }
}
