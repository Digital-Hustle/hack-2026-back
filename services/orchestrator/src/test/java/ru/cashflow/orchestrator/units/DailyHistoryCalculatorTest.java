package ru.cashflow.orchestrator.units;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.ledger.command.DailyHistoryCalculator;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DayStatus;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;

import static org.assertj.core.api.Assertions.assertThat;

class DailyHistoryCalculatorTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant KNOWN = Instant.parse("2026-10-01T00:00:00Z");
    private static final LocalDate D1 = LocalDate.parse("2026-09-01");

    @Test
    void everyDayExactlyOnceAndUnknownDaysStayMissingNotZero() {
        var days = DailyHistoryCalculator.calculate(D1, D1.plusDays(4),
                List.of(payment(D1, "10000.00", Direction.IN), payment(D1, "8000.00", Direction.OUT)),
                List.of(anchor(D1, "102000.00", KNOWN)), List.of(range(D1, D1.plusDays(1), Completeness.CONFIRMED_COMPLETE, KNOWN)));

        assertThat(days).extracting(d -> d.date()).containsExactly(D1, D1.plusDays(1), D1.plusDays(2), D1.plusDays(3),
                D1.plusDays(4));
        assertThat(days.get(0).status()).isEqualTo(DayStatus.OBSERVED);
        assertThat(days.get(0).inflow()).isEqualByComparingTo("10000.00");
        assertThat(days.get(0).outflow()).isEqualByComparingTo("8000.00");
        assertThat(days.get(0).paymentCount()).isEqualTo(2);
        assertThat(days.get(1).status()).isEqualTo(DayStatus.ZERO_CONFIRMED);
        assertThat(days.get(1).inflow()).hasToString("0.00");
        assertThat(days.get(2).status()).isEqualTo(DayStatus.MISSING);
        assertThat(days.get(2).inflow()).isNull();
        assertThat(days.get(2).balanceEod()).isNull();
    }

    @Test
    void missingDayKeepsKnownPaymentCountButIsNotComplete() {
        var days = DailyHistoryCalculator.calculate(D1, D1, List.of(payment(D1, "5.00", Direction.IN)), List.of(),
                List.of());

        assertThat(days.getFirst().status()).isEqualTo(DayStatus.MISSING);
        assertThat(days.getFirst().paymentCount()).isEqualTo(1);
        assertThat(days.getFirst().inflow()).isNull();
    }

    @Test
    void laterUnknownRangeOverridesEarlierCompleteRange() {
        var days = DailyHistoryCalculator.calculate(D1, D1.plusDays(1), List.of(), List.of(), List.of(
                range(D1, D1.plusDays(1), Completeness.CONFIRMED_COMPLETE, KNOWN),
                range(D1.plusDays(1), D1.plusDays(1), Completeness.UNKNOWN, KNOWN.plusSeconds(60))));

        assertThat(days).extracting(d -> d.status()).containsExactly(DayStatus.ZERO_CONFIRMED, DayStatus.MISSING);
    }

    @Test
    void mismatchFlaggedWhenEodDoesNotFollowFlows() {
        var days = DailyHistoryCalculator.calculate(D1, D1.plusDays(1),
                List.of(payment(D1.plusDays(1), "100.00", Direction.IN)),
                List.of(anchor(D1, "1000.00", KNOWN), anchor(D1.plusDays(1), "1500.00", KNOWN)),
                List.of(range(D1, D1.plusDays(1), Completeness.CONFIRMED_COMPLETE, KNOWN)));

        assertThat(days.get(0).balanceMismatch()).isFalse();
        assertThat(days.get(1).balanceMismatch()).isTrue();
    }

    @Test
    void latestKnownAnchorForDateWins() {
        var resolved = DailyHistoryCalculator.resolveAnchors(List.of(anchor(D1, "100.00", KNOWN),
                anchor(D1, "250.00", KNOWN.plusSeconds(10))));

        assertThat(resolved.get(D1)).isEqualByComparingTo("250.00");
    }

    @Test
    void startBalanceRollsForwardOnlyThroughCompleteDays() {
        var complete = DailyHistoryCalculator.calculate(D1, D1.plusDays(2),
                List.of(payment(D1.plusDays(1), "300.00", Direction.IN), payment(D1.plusDays(2), "50.00", Direction.OUT)),
                List.of(anchor(D1, "1000.00", KNOWN)),
                List.of(range(D1, D1.plusDays(2), Completeness.CONFIRMED_COMPLETE, KNOWN)));
        var start = DailyHistoryCalculator.startBalance(D1.plusDays(2), List.of(anchor(D1, "1000.00", KNOWN)), complete);

        assertThat(start).isPresent();
        assertThat(start.get().balance()).isEqualByComparingTo("1250.00");
        assertThat(start.get().anchorDate()).isEqualTo(D1);

        var withGap = DailyHistoryCalculator.calculate(D1, D1.plusDays(2), List.of(), List.of(anchor(D1, "1000.00", KNOWN)),
                List.of(range(D1, D1, Completeness.CONFIRMED_COMPLETE, KNOWN)));
        assertThat(DailyHistoryCalculator.startBalance(D1.plusDays(2), List.of(anchor(D1, "1000.00", KNOWN)), withGap))
                .isEmpty();
        assertThat(DailyHistoryCalculator.startBalance(D1.minusDays(1), List.of(anchor(D1, "1000.00", KNOWN)), complete))
                .isEmpty();
    }

    @Test
    void counterpartyKeyPrefersInnAndIgnoresDisplayFormatting() {
        assertThat(CounterpartyKeys.of("0012345678", "ООО «Ромашка»")).isEqualTo("inn:0012345678");
        assertThat(CounterpartyKeys.of("0012345678", "Ромашка (новое имя)")).isEqualTo("inn:0012345678");
        assertThat(CounterpartyKeys.of(null, "ООО  «Ёлка»")).isEqualTo(CounterpartyKeys.of("", "ооо елка"));
        assertThat(CounterpartyKeys.of(null, "  ")).isNull();
    }

    private static Payment payment(LocalDate date, String amount, Direction direction) {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), ACCOUNT, null, null, null, date, new BigDecimal(amount),
                direction, null, null, null, null, "OTHER", CategorySource.OTHER, null, null, 0, KNOWN, KNOWN);
    }

    private static BalanceAnchor anchor(LocalDate date, String balance, Instant known) {
        return new BalanceAnchor(UUID.randomUUID(), ACCOUNT, date, new BigDecimal(balance), AnchorSource.STATEMENT,
                "statement", known, null, null);
    }

    private static CompletenessRange range(LocalDate from, LocalDate to, Completeness completeness, Instant known) {
        return new CompletenessRange(UUID.randomUUID(), ACCOUNT, from, to, completeness, "test", known, null);
    }
}
