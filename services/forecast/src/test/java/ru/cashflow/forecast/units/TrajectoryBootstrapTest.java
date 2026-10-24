package ru.cashflow.forecast.units;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.RecurringCalendar;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.risk.application.TrajectoryBootstrap;
import ru.cashflow.forecast.features.risk.model.CalibrationOrigin;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.Direction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrajectoryBootstrapTest {
    private final TrajectoryBootstrap bootstrap = new TrajectoryBootstrap(TestInputs.engine(),
            new ResidualExtractor(new RecurringCalendar()), new SnapshotValidator(TestInputs.CLOCK));

    @Test
    void insufficientOriginsReturnNullProbabilityAndNoScenarios() throws Exception {
        var input = new CalculationInput(TestInputs.point(), "a".repeat(64), List.of());
        var result = bootstrap.estimate(input, TestInputs.engine().calculate(input.pointInput()));
        assertThat(result.estimate().availability()).isEqualTo(RiskAvailability.INSUFFICIENT_HISTORY);
        assertThat(result.estimate().probability()).isNull();
        assertThat(result.estimate().scenarioCount()).isZero();
        assertThat(result.days()).hasSize(30).allSatisfy(day -> assertThat(day.balanceP50()).isNull());
    }

    @Test
    void pairedTrajectoriesClipResidualsAndRepeatExactlyWithSameSeed() throws Exception {
        var input = inputWithOrigins(12);
        var point = TestInputs.engine().calculate(input.pointInput());
        var result = bootstrap.estimate(input, point);
        assertThat(result.estimate().availability()).isEqualTo(RiskAvailability.AVAILABLE);
        assertThat(result.estimate().probability()).isEqualTo(1.0);
        assertThat(result.estimate().probabilityCalibrated()).isFalse();
        assertThat(result.scenarios()).hasSize(2000);
        assertThat(result.scenarios()).allSatisfy(path -> {
            assertThat(path).hasSize(30);
            assertThat(path.get(0)).isEqualTo("100000.00");
            assertThat(path.get(4)).isEqualTo("-60000.00");
            assertThat(path.get(29)).isEqualTo("-60000.00");
        });
        assertThat(result.days().get(4).balanceP10()).isEqualTo("-60000.00");
        assertThat(bootstrap.estimate(input, point)).isEqualTo(result);
    }

    @Test
    void rejectsUnmaturedDuplicateAndIncompatibleOriginsBeforeFitting() throws Exception {
        var valid = inputWithOrigins(12);
        var origins = new ArrayList<>(valid.calibrationOrigins());
        origins.add(origins.getFirst());
        var duplicate = new CalculationInput(valid.pointInput(), valid.fingerprint(), origins);
        assertThatThrownBy(() -> bootstrap.estimate(duplicate, TestInputs.engine().calculate(valid.pointInput())))
                .isInstanceOf(InputException.class);
        var futureInput = TestInputs.point(TestInputs.synthetic(LocalDate.of(2026, 10, 1), 7, "10000.00", "8000.00"));
        var unmatured = new CalculationInput(valid.pointInput(), valid.fingerprint(),
                List.of(new CalibrationOrigin(futureInput, "a".repeat(64), List.of(), List.of())));
        assertThatThrownBy(() -> bootstrap.estimate(unmatured, TestInputs.engine().calculate(valid.pointInput())))
                .isInstanceOf(InputException.class);
    }

    @Test
    void incompleteScoringOriginsMakeRiskUnavailableWithoutInventingZeros() throws Exception {
        var valid = inputWithOrigins(12);
        var origins = new ArrayList<>(valid.calibrationOrigins());
        var first = origins.removeFirst();
        var days = new ArrayList<>(first.actualDays());
        var day = days.getFirst();
        days.set(0, new DayFact(day.date(), null, null, null, DailyStatus.MISSING, 0, false));
        origins.add(new CalibrationOrigin(first.input(), first.fingerprint(), days, List.of()));
        var input = new CalculationInput(valid.pointInput(), valid.fingerprint(), origins);
        var result = bootstrap.estimate(input, TestInputs.engine().calculate(input.pointInput()));
        assertThat(result.estimate().availability()).isEqualTo(RiskAvailability.INPUT_INCOMPLETE);
        assertThat(result.estimate().probability()).isNull();
        assertThat(result.estimate().scenarioCount()).isZero();
    }

    @Test
    void samplesWholeMixedTrajectoriesAndPairedDirectionsWithKnownSeed() throws Exception {
        var valid = inputWithOrigins(12);
        var outer = TestInputs.point(node -> {
            node.withArray("knownPlans").removeAll();
            node.put("startBalance", "0.00");
        });
        var origins = new ArrayList<CalibrationOrigin>();
        for (int index = 0; index < 12; index++) {
            var origin = valid.calibrationOrigins().get(index);
            if (index < 6) {
                origins.add(origin);
                continue;
            }
            var days = new ArrayList<DayFact>();
            var payments = new ArrayList<Payment>();
            for (int horizon = 1; horizon <= 30; horizon++) {
                var date = origin.input().asOfDate().plusDays(horizon);
                days.add(new DayFact(date, new java.math.BigDecimal("1.00"), new java.math.BigDecimal("2.00"), null, DailyStatus.OBSERVED, 2, false));
                payments.add(new Payment(java.util.UUID.nameUUIDFromBytes((date + "IN").getBytes(java.nio.charset.StandardCharsets.UTF_8)), date,
                        new java.math.BigDecimal("1.00"), Direction.IN, "SALES", "client-a"));
                payments.add(new Payment(java.util.UUID.nameUUIDFromBytes((date + "OUT").getBytes(java.nio.charset.StandardCharsets.UTF_8)), date,
                        new java.math.BigDecimal("2.00"), Direction.OUT, "SUPPLIERS", "supplier-a"));
            }
            origins.add(new CalibrationOrigin(origin.input(), origin.fingerprint(), days, payments));
        }
        var input = new CalculationInput(outer, "a".repeat(64), origins);
        var result = bootstrap.estimate(input, TestInputs.engine().calculate(outer));
        // Independently verified SHA-256 + Java LCG48 vector: 989 of 2000 draws from indices 0..5.
        assertThat(result.estimate().probability()).isEqualTo(0.4945);
        assertThat(result.scenarios().get(0).getLast()).isEqualTo("-30.00");
        assertThat(result.scenarios().get(1).getLast()).isEqualTo("0.00");
        assertThat(result.scenarios()).allSatisfy(path -> {
            assertThat(path.getFirst()).isIn(new java.math.BigDecimal("0.00"), new java.math.BigDecimal("-1.00"));
            assertThat(path.getLast()).isEqualTo(path.getFirst().multiply(new java.math.BigDecimal("30")));
        });
        assertThat(result.days().getLast().balanceP10()).isEqualTo("-30.00");
        assertThat(result.days().getLast().balanceP90()).isEqualTo("0.00");
        assertThat(result.days()).allSatisfy(day -> assertThat(day.pBelowZero()).isEqualTo(result.estimate().probability()));
    }

    @Test
    void allowsUnavailableOriginsToBeOmittedFromSevenDayGridButRejectsOffGridCutoff() throws Exception {
        var input = inputWithOrigins(13);
        var origins = new ArrayList<>(input.calibrationOrigins());
        origins.remove(5);
        var gridWithHole = new CalculationInput(input.pointInput(), input.fingerprint(), origins);
        assertThat(bootstrap.estimate(gridWithHole, TestInputs.engine().calculate(input.pointInput())).estimate().availability())
                .isEqualTo(RiskAvailability.AVAILABLE);
        var first = origins.removeFirst();
        var wrongDate = first.input().asOfDate().minusDays(1);
        var node = TestInputs.synthetic(wrongDate, 7, "10000.00", "8000.00");
        node.put("scope", "WITH_KNOWN_PLANS");
        origins.add(new CalibrationOrigin(TestInputs.point(node), first.fingerprint(), first.actualDays(), first.actualPayments()));
        var offGrid = new CalculationInput(input.pointInput(), input.fingerprint(), origins);
        assertThatThrownBy(() -> bootstrap.estimate(offGrid, TestInputs.engine().calculate(input.pointInput())))
                .isInstanceOf(InputException.class);
    }

    static CalculationInput inputWithOrigins(int count) throws Exception {
        var outer = TestInputs.point();
        var origins = new ArrayList<CalibrationOrigin>();
        for (int index = 0; index < count; index++) {
            LocalDate date = outer.asOfDate().minusDays(30L + index * 7L);
            var node = TestInputs.synthetic(date, 7, "10000.00", "8000.00");
            node.put("scope", "WITH_KNOWN_PLANS");
            var originInput = TestInputs.point(node);
            var outcomes = new ArrayList<DayFact>();
            for (int horizon = 1; horizon <= 30; horizon++) {
                outcomes.add(new DayFact(date.plusDays(horizon), Money.ZERO, Money.ZERO, new java.math.BigDecimal("100000.00"), DailyStatus.ZERO_CONFIRMED, 0, false));
            }
            origins.add(new CalibrationOrigin(originInput, "a".repeat(64), outcomes, List.of()));
        }
        return new CalculationInput(outer, "a".repeat(64), origins);
    }
}
