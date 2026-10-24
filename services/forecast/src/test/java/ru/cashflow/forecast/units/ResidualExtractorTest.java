package ru.cashflow.forecast.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import ru.cashflow.forecast.features.forecast.application.RecurringCalendar;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.forecast.model.StreamKey;
import ru.cashflow.forecast.features.shared.model.BusinessDayRule;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class ResidualExtractorTest {
    private final ResidualExtractor extractor = new ResidualExtractor(new RecurringCalendar());
    private final LocalDate monday = LocalDate.of(2026, 10, 19);

    @Test
    void removesOnlyConcreteRecurringPaymentNotEveryPaymentOfCounterparty() throws Exception {
        var recurring = payment("1", monday, "100.00", Direction.OUT, "RENT", "landlord");
        var unrelated = payment("2", monday, "15.00", Direction.OUT, "OTHER", "landlord");
        var day =
                new DayFact(
                        monday,
                        Money.ZERO,
                        new BigDecimal("115.00"),
                        null,
                        DailyStatus.OBSERVED,
                        2,
                        false);
        var input = withPattern(TestInputs.point());
        var result =
                extractor.extract(
                        input, List.of(day), List.of(unrelated, recurring), Set.of(), false);
        assertThat(result.days().get(monday).outflow()).isEqualTo("15.00");
        assertThat(result.excludedPaymentIds()).containsExactly(recurring.paymentId());
    }

    @Test
    void doesNotSubtractTheoreticalCalendarAmountFromUnmatchedHistoricalDay() throws Exception {
        var unrelated = payment("1", monday, "75.00", Direction.OUT, "RENT", "landlord");
        var day =
                new DayFact(
                        monday,
                        Money.ZERO,
                        new BigDecimal("75.00"),
                        null,
                        DailyStatus.OBSERVED,
                        1,
                        false);
        var result =
                extractor.extract(
                        withPattern(TestInputs.point()),
                        List.of(day),
                        List.of(unrelated),
                        Set.of(),
                        false);
        assertThat(result.days().get(monday).outflow()).isEqualTo("75.00");
    }

    @Test
    void baselineReplacementExcludesOnlyLinkedDirectionAndMissingRemainsUnknown() throws Exception {
        var inflow = payment("1", monday, "100.00", Direction.IN, "SALES", "client-a");
        var outflow = payment("2", monday, "20.00", Direction.OUT, "OTHER", "client-a");
        var day =
                new DayFact(
                        monday,
                        new BigDecimal("100.00"),
                        new BigDecimal("20.00"),
                        null,
                        DailyStatus.OBSERVED,
                        2,
                        false);
        var missing =
                new DayFact(monday.plusDays(1), null, null, null, DailyStatus.MISSING, 0, false);
        var result =
                extractor.extract(
                        TestInputs.point(),
                        List.of(day, missing),
                        List.of(inflow, outflow),
                        Set.of(new StreamKey(Direction.IN, "client-a")),
                        false);
        assertThat(result.days().get(monday).inflow()).isEqualTo("0.00");
        assertThat(result.days().get(monday).outflow()).isEqualTo("20.00");
        assertThat(result.days()).doesNotContainKey(monday.plusDays(1));
    }

    @Test
    void rejectsAmbiguousMatchingRatherThanCountingEconomicPaymentTwice() throws Exception {
        var first = payment("1", monday, "100.00", Direction.OUT, "RENT", "landlord");
        var second = payment("2", monday, "100.00", Direction.OUT, "RENT", "landlord");
        var day =
                new DayFact(
                        monday,
                        Money.ZERO,
                        new BigDecimal("200.00"),
                        null,
                        DailyStatus.OBSERVED,
                        2,
                        false);
        var input = withPattern(TestInputs.point());
        assertThatThrownBy(
                        () ->
                                extractor.extract(
                                        input,
                                        List.of(day),
                                        List.of(first, second),
                                        Set.of(),
                                        false))
                .isInstanceOf(InputException.class);
    }

    @Test
    void scoringOfReplacedWeekendInstanceUsesResolvedDateInsteadOfTreatingPlanAsResidual()
            throws Exception {
        var node = TestInputs.fixture("calculate.json");
        node.withArray("recurringPatterns")
                .add(
                        TestInputs.JSON.valueToTree(
                                RecurringCalendarTest.pattern(
                                        Frequency.WEEKLY, 7, null, BusinessDayRule.FOLLOWING)));
        var plan =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        node.withArray("knownPlans").get(0);
        plan.put("relation", "REPLACES_RECURRING");
        plan.put("recurringPatternId", "66666666-6666-4666-8666-666666666666");
        plan.put("counterpartyKey", "landlord");
        plan.put("categoryCode", "RENT");
        plan.put("dueDate", "2026-10-25");
        var date = LocalDate.of(2026, 10, 26);
        var payment = payment("fixed-plan", date, "160000.00", Direction.OUT, "RENT", "landlord");
        var day =
                new DayFact(
                        date,
                        Money.ZERO,
                        new BigDecimal("160000.00"),
                        null,
                        DailyStatus.OBSERVED,
                        1,
                        false);
        var result =
                extractor.extract(
                        TestInputs.point(node), List.of(day), List.of(payment), Set.of(), true);
        assertThat(result.days().get(date).outflow()).isEqualTo("0.00");
        assertThat(result.excludedPaymentIds()).containsExactly(payment.paymentId());
    }

    @Test
    void scoringPlanPaidOnItsExplicitWeekendDateDoesNotLeakIntoResidualError() throws Exception {
        var node = TestInputs.fixture("calculate.json");
        node.withArray("recurringPatterns")
                .add(
                        TestInputs.JSON.valueToTree(
                                RecurringCalendarTest.pattern(
                                        Frequency.WEEKLY, 7, null, BusinessDayRule.FOLLOWING)));
        var plan =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        node.withArray("knownPlans").get(0);
        plan.put("relation", "REPLACES_RECURRING");
        plan.put("recurringPatternId", "66666666-6666-4666-8666-666666666666");
        plan.put("counterpartyKey", "landlord");
        plan.put("categoryCode", "RENT");
        plan.put("dueDate", "2026-10-25");
        var date = LocalDate.of(2026, 10, 25);
        var paid = payment("explicit-plan", date, "160000.00", Direction.OUT, "RENT", "landlord");
        var day =
                new DayFact(
                        date,
                        Money.ZERO,
                        new BigDecimal("160000.00"),
                        null,
                        DailyStatus.OBSERVED,
                        1,
                        false);
        assertThat(
                        extractor
                                .extract(
                                        TestInputs.point(node),
                                        List.of(day),
                                        List.of(paid),
                                        Set.of(),
                                        true)
                                .days()
                                .get(date)
                                .outflow())
                .isEqualTo("0.00");
    }

    private ForecastInput withPattern(ForecastInput input) {
        return new ForecastInput(
                input.snapshotId(),
                input.companyId(),
                input.accountId(),
                input.asOfDate(),
                input.knowledgeCutoff(),
                input.timezone(),
                input.inputDataVersion(),
                input.algorithmVersion(),
                input.settings(),
                input.scope(),
                input.startBalance(),
                input.startBalanceDate(),
                input.historyFrom(),
                input.dailyHistory(),
                input.payments(),
                input.knownPlans(),
                List.of(
                        RecurringCalendarTest.pattern(
                                Frequency.WEEKLY, 1, null, BusinessDayRule.NONE)),
                input.completeRanges());
    }

    private Payment payment(
            String id,
            LocalDate date,
            String amount,
            Direction direction,
            String category,
            String key) {
        return new Payment(
                UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                date,
                new BigDecimal(amount),
                direction,
                category,
                key);
    }
}
