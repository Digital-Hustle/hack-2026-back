package ru.cashflow.forecast.units;

import java.time.LocalDate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.api.dto.request.BacktestSnapshot;
import ru.cashflow.forecast.features.backtest.application.BacktestService;
import ru.cashflow.forecast.features.forecast.application.GapDetector;
import ru.cashflow.forecast.features.forecast.application.RecurringCalendar;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.risk.application.TrajectoryBootstrap;
import ru.cashflow.forecast.features.shared.model.InputException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BacktestServiceTest {
    private final BacktestService service = new BacktestService(TestInputs.engine(), new TrajectoryBootstrap(TestInputs.engine(),
            new ResidualExtractor(new RecurringCalendar()), new SnapshotValidator(TestInputs.CLOCK)), new GapDetector(), new SnapshotValidator(TestInputs.CLOCK));

    @Test
    void fixtureHasHandComputedMae31000AndAllThirtyHorizonMetrics() throws Exception {
        var result = evaluate(TestInputs.fixture("backtest.json"));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.evaluatedOrigins()).isEqualTo(1);
        assertThat(result.skippedOrigins()).isZero();
        assertThat(result.evaluatedDays()).isEqualTo(30);
        assertThat(result.maeBalance()).isEqualTo("31000.00");
        assertThat(result.rmseBalance()).isEqualTo("35505.87");
        assertThat(result.horizons()).hasSize(30);
        assertThat(result.horizons().getFirst().maeBalance()).isEqualTo("2000.00");
        assertThat(result.horizons().get(6).maeBalance()).isEqualTo("14000.00");
        assertThat(result.horizons().getLast().maeBalance()).isEqualTo("60000.00");
        assertThat(result.gapMetrics().recall()).isNull();
        assertThat(result.riskMetrics().brierScore()).isNull();
        assertThat(result.origins()).singleElement().satisfies(origin -> assertThat(origin.status()).isEqualTo("EVALUATED"));
        var quality = result.quality();
        assertThat(quality.primary().model()).isEqualTo("WEEKDAY_MEAN_V1@12w");
        assertThat(quality.primary().maeBalance()).isEqualTo("31000.00");
        assertThat(quality.primary().mseBalance()).isEqualTo("1260666666.67");
        assertThat(quality.primary().dailyMatrix().total()).isEqualTo(30);
        assertThat(quality.benchmarks()).extracting(score -> score.model())
                .contains("NAIVE_LAST_BALANCE", "SEASONAL_NAIVE_7D", "WEEKDAY_ROBUST_V2@12w");
        assertThat(result.origins().getFirst().days()).hasSize(30);
        assertThat(result.trace()).extracting(step -> step.stage()).contains("EXPERIMENT", "ORIGIN", "METRICS", "CONFUSION_MATRIX");
    }

    @Test
    void missingSnapshotsAreSkippedAndLastOriginOffGridIsNotAdded() throws Exception {
        ObjectNode node = TestInputs.fixture("backtest.json");
        ((ObjectNode) node.get("request")).put("lastOrigin", "2026-09-10");
        var result = evaluate(node);
        assertThat(result.origins()).extracting(origin -> origin.asOfDate())
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8));
        assertThat(result.skippedOrigins()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PARTIAL");
    }

    @Test
    void missingEodIsExcludedButZeroEodIsScoredAndRiskUnavailableIsPartial() throws Exception {
        ObjectNode node = TestInputs.fixture("backtest.json");
        ((ObjectNode) node.withArray("actualDays").get(0)).putNull("balanceEod");
        ((ObjectNode) node.withArray("actualDays").get(1)).put("balanceEod", "0.00");
        ((ObjectNode) node.get("request")).put("evaluateRisk", true);
        var result = evaluate(node);
        assertThat(result.evaluatedDays()).isEqualTo(29);
        assertThat(result.horizons().getFirst().evaluatedCount()).isZero();
        assertThat(result.horizons().get(1).maeBalance()).isEqualTo("104000.00");
        assertThat(result.status()).isEqualTo("PARTIAL");
    }

    @Test
    void rejectsAllSkippedDuplicateOriginsAndExperimentSettingsMismatch() throws Exception {
        ObjectNode empty = TestInputs.fixture("backtest.json");
        empty.withArray("originSnapshots").removeAll();
        assertThatThrownBy(() -> evaluate(empty)).isInstanceOf(InputException.class);
        ObjectNode duplicate = TestInputs.fixture("backtest.json");
        duplicate.withArray("originSnapshots").add(duplicate.withArray("originSnapshots").get(0));
        assertThatThrownBy(() -> evaluate(duplicate)).isInstanceOf(InputException.class);
        ObjectNode mismatch = TestInputs.fixture("backtest.json");
        ((ObjectNode) mismatch.get("settings")).put("windowWeeks", 8);
        assertThatThrownBy(() -> evaluate(mismatch)).isInstanceOf(InputException.class);
    }

    @Test
    void nestedCalibrationMustMatureAtOwnOriginNotAtLastOriginOfExperiment() throws Exception {
        ObjectNode node = TestInputs.fixture("backtest.json");
        ((ObjectNode) node.get("request")).put("lastOrigin", "2026-09-30");
        ((ObjectNode) node.get("request")).put("evaluateRisk", true);
        var first = (ObjectNode) node.withArray("originSnapshots").get(0);
        var origin = first.withArray("calibrationOrigins").addObject();
        // Mature by lastOrigin but not by the first origin 2026-09-01.
        var nested = TestInputs.synthetic(LocalDate.of(2026, 8, 20), 7, "0.00", "0.00");
        origin.set("input", nested);
        var outcomes = origin.putArray("actualDays");
        for (int day = 1; day <= 30; day++) {
            var actual = outcomes.addObject();
            actual.put("date", LocalDate.of(2026, 8, 20).plusDays(day).toString());
            actual.put("status", "ZERO_CONFIRMED");
            actual.put("inflow", "0.00");
            actual.put("outflow", "0.00");
            actual.putNull("balanceEod");
            actual.put("paymentCount", 0);
            actual.put("balanceMismatch", false);
        }
        origin.putArray("actualPayments");
        assertThatThrownBy(() -> evaluate(node)).isInstanceOf(InputException.class)
                .satisfies(error -> assertThat(((InputException) error).field()).isEqualTo("calibrationOrigins"));
    }

    private ru.cashflow.forecast.features.backtest.model.BacktestResult evaluate(ObjectNode node) throws Exception {
        return service.evaluate(TestInputs.MAPPER.toBacktestInput(TestInputs.JSON.treeToValue(node, BacktestSnapshot.class)));
    }
}
