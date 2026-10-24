package ru.cashflow.forecast.units;

import java.time.DayOfWeek;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.HistoryPreparer;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import ru.cashflow.forecast.features.shared.model.InputException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryPreparerTest {
    private final HistoryPreparer preparer = new HistoryPreparer();

    @Test
    void includesConfirmedZeroDaysButNotMissingDaysInWeekdayCounts() throws Exception {
        ObjectNode node = TestInputs.synthetic(LocalDate.of(2026, 10, 24), 14, "0.00", "0.00");
        ObjectNode missing = (ObjectNode) node.withArray("dailyHistory").get(0);
        missing.put("status", "MISSING");
        missing.putNull("inflow");
        missing.putNull("outflow");
        missing.putNull("balanceEod");
        var result = preparer.prepare(TestInputs.point(node));
        assertThat(result.sampleCounts().get(DayOfWeek.SUNDAY)).isEqualTo(1);
        assertThat(result.sampleCounts().get(DayOfWeek.MONDAY)).isEqualTo(2);
        assertThat(result.quality()).isEqualTo(DataQuality.LIMITED);
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("SHORT_HISTORY", "MISSING_DATA");
    }

    @Test
    void limitsTrainingToConfiguredWindowAndMarksFullHistoryGood() throws Exception {
        ObjectNode node = TestInputs.synthetic(LocalDate.of(2026, 10, 24), 100, "1.00", "0.00");
        ((ObjectNode) node.get("settings")).put("windowWeeks", 8);
        var result = preparer.prepare(TestInputs.point(node));
        assertThat(result.days()).hasSize(56);
        assertThat(result.payments()).hasSize(56);
        assertThat(result.sampleCounts().values()).containsOnly(8);
        assertThat(result.quality()).isEqualTo(DataQuality.GOOD);
    }

    @Test
    void rejectsUnavailableWeekdayInsteadOfInventingZero() throws Exception {
        var input = TestInputs.point(TestInputs.synthetic(LocalDate.of(2026, 10, 24), 6, "1.00", "0.00"));
        assertThatThrownBy(() -> preparer.prepare(input)).isInstanceOf(InputException.class)
                .satisfies(error -> assertThat(((InputException) error).code()).isEqualTo("INSUFFICIENT_HISTORY"));
    }

    @Test
    void warnsOnObservedBalanceMismatchWithoutChangingFacts() throws Exception {
        var input = TestInputs.point(node -> ((ObjectNode) node.withArray("dailyHistory").get(2)).put("balanceEod", "1.00"));
        var result = preparer.prepare(input);
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("BALANCE_MISMATCH");
        assertThat(result.days().get(2).balanceEod()).isEqualByComparingTo("1.00");
    }
}
