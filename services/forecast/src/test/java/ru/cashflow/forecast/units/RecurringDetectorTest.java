package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.recurring.application.RecurringDetector;
import ru.cashflow.forecast.features.recurring.model.DetectionInput;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.PatternStatus;
import ru.cashflow.forecast.features.shared.model.Payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurringDetectorTest {
    private final RecurringDetector detector = new RecurringDetector(TestInputs.CLOCK);
    private final UUID account = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void detectsWeeklyGroupAndUsesMedianOfLastThreeAmountsWithStableId() {
        var payments = payments("2026-10-05", "2026-10-12", "2026-10-19");
        var result = detector.detect(input(payments));
        assertThat(result.patterns()).singleElement().satisfies(pattern -> {
            assertThat(pattern.status()).isEqualTo(PatternStatus.DETECTED);
            assertThat(pattern.schedule().frequency()).isEqualTo(Frequency.WEEKLY);
            assertThat(pattern.schedule().weekday()).isEqualTo(1);
            assertThat(pattern.schedule().dayOfMonth()).isNull();
            assertThat(pattern.amount()).isEqualTo("110.00");
            assertThat(pattern.observations()).isEqualTo(3);
        });
        var reversed = new ArrayList<>(payments);
        Collections.reverse(reversed);
        assertThat(detector.detect(input(reversed))).isEqualTo(result);
    }

    @Test
    void detectsMonthlyAndRejectsDailyOrIrregularAmounts() {
        var monthly = detector.detect(input(payments("2026-07-10", "2026-08-10", "2026-09-10")));
        assertThat(monthly.patterns()).singleElement().satisfies(pattern -> {
            assertThat(pattern.schedule().frequency()).isEqualTo(Frequency.MONTHLY);
            assertThat(pattern.schedule().dayOfMonth()).isEqualTo(10);
        });
        assertThat(detector.detect(input(payments("2026-10-10", "2026-10-11", "2026-10-12"))).patterns()).isEmpty();
        var irregular = new ArrayList<>(payments("2026-10-05", "2026-10-12", "2026-10-19"));
        var last = irregular.get(2);
        irregular.set(2, new Payment(last.paymentId(), last.date(), new BigDecimal("5000.00"), last.direction(), last.categoryCode(), last.counterpartyKey()));
        assertThat(detector.detect(input(irregular)).patterns()).isEmpty();
    }

    @Test
    void separatesCategoryDirectionAndCounterpartyInsteadOfGroupingByBankOrAmount() {
        var payments = payments("2026-10-05", "2026-10-12", "2026-10-19");
        var split = new ArrayList<>(payments);
        var last = split.removeLast();
        split.add(new Payment(last.paymentId(), last.date(), last.amount(), Direction.IN, "SALARY", last.counterpartyKey()));
        assertThat(detector.detect(input(split)).patterns()).isEmpty();
    }

    @Test
    void rejectsFuturePaymentsAndDuplicateIdentities() {
        assertThatThrownBy(() -> detector.detect(input(payments("2026-10-26", "2026-11-02", "2026-11-09"))))
                .isInstanceOf(InputException.class);
        var duplicate = new ArrayList<>(payments("2026-10-05", "2026-10-12", "2026-10-19"));
        duplicate.add(duplicate.get(0));
        assertThatThrownBy(() -> detector.detect(input(duplicate))).isInstanceOf(InputException.class);
    }

    private DetectionInput input(List<Payment> payments) {
        return new DetectionInput(account, LocalDate.of(2026, 10, 24), 42, payments);
    }

    private List<Payment> payments(String... dates) {
        var result = new ArrayList<Payment>();
        for (int index = 0; index < dates.length; index++) {
            result.add(new Payment(UUID.nameUUIDFromBytes(dates[index].getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    LocalDate.parse(dates[index]), new BigDecimal(100 + index * 10).setScale(2), Direction.OUT, "RENT", "landlord"));
        }
        return result;
    }
}
