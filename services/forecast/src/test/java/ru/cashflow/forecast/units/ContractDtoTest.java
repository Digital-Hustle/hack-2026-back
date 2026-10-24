package ru.cashflow.forecast.units;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import ru.cashflow.forecast.api.dto.request.BacktestSnapshot;
import ru.cashflow.forecast.api.dto.request.CalibrationOriginSnapshot;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.api.dto.request.RecurringDetectionInput;
import ru.cashflow.forecast.api.json.ForecastJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractDtoTest {

    private final ObjectMapper json = ForecastJson.mapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void calculateFixtureKeepsMoneyExactAndPlansSeparateFromFacts() throws IOException {
        ForecastSnapshot snapshot = read("calculate.json", ForecastSnapshot.class);

        assertThat(validator.validate(snapshot)).isEmpty();
        assertThat(snapshot.asOfDate()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(snapshot.startBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(snapshot.dailyHistory()).hasSize(7);
        assertThat(snapshot.payments()).hasSize(14);
        assertThat(snapshot.knownPlans()).singleElement()
                .satisfies(plan -> assertThat(plan.remainingAmount()).isEqualByComparingTo("160000.00"));
        assertThat(snapshot.calibrationOrigins()).isEmpty();
    }

    @Test
    void calibrationOriginFixtureCarriesThirtyScoringDaysSeparateFromInput() throws IOException {
        CalibrationOriginSnapshot origin = read("calibration-origin.json", CalibrationOriginSnapshot.class);

        assertThat(validator.validate(origin)).isEmpty();
        assertThat(origin.input().asOfDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(origin.input().calibrationOrigins()).isEmpty();
        assertThat(origin.actualDays()).hasSize(30);
        assertThat(origin.actualDays().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 2));
    }

    @Test
    void backtestAndRecurringFixturesDeserializeAndValidate() throws IOException {
        BacktestSnapshot backtest = read("backtest.json", BacktestSnapshot.class);
        RecurringDetectionInput recurring = read("recurring-detection.json", RecurringDetectionInput.class);

        assertThat(validator.validate(backtest)).isEmpty();
        assertThat(backtest.originSnapshots()).hasSize(1);
        assertThat(backtest.actualDays()).hasSize(30);
        assertThat(validator.validate(recurring)).isEmpty();
        assertThat(recurring.payments()).hasSize(7);
    }

    @Test
    void rejectsUnknownFieldsInsteadOfSilentlyIgnoringThem() throws IOException {
        ObjectNode node = (ObjectNode) json.readTree(fixture("calculate.json"));
        node.put("futureFacts", "leak");

        assertThatThrownBy(() -> json.treeToValue(node, ForecastSnapshot.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void rejectsMoneyWithoutExactlyTwoDecimalsAndNumericMoney() throws IOException {
        ObjectNode threeDecimals = (ObjectNode) json.readTree(fixture("calculate.json"));
        threeDecimals.put("startBalance", "100000.001");
        ObjectNode numeric = (ObjectNode) json.readTree(fixture("calculate.json"));
        numeric.put("startBalance", 100000.00);

        assertThat(violations(json.treeToValue(threeDecimals, ForecastSnapshot.class))).contains("startBalance");
        assertThatThrownBy(() -> json.treeToValue(numeric, ForecastSnapshot.class))
                .hasMessageContaining("startBalance");
    }

    @Test
    void requiresArraysAndRejectsUnsupportedSettings() throws IOException {
        ObjectNode missingArray = (ObjectNode) json.readTree(fixture("calculate.json"));
        missingArray.remove("calibrationOrigins");
        ObjectNode badWindow = (ObjectNode) json.readTree(fixture("calculate.json"));
        ((ObjectNode) badWindow.get("settings")).put("windowWeeks", 9);
        ObjectNode badHorizon = (ObjectNode) json.readTree(fixture("calculate.json"));
        ((ObjectNode) badHorizon.get("settings")).put("horizonDays", 31);

        assertThatThrownBy(() -> json.treeToValue(missingArray, ForecastSnapshot.class))
                .hasMessageContaining("calibrationOrigins");
        assertThat(violations(json.treeToValue(badWindow, ForecastSnapshot.class))).contains("settings.windowWeeks");
        assertThat(violations(json.treeToValue(badHorizon, ForecastSnapshot.class))).contains("settings.horizonDays");
    }

    @Test
    void rejectsNonPositivePaymentAmountAndMalformedFingerprint() throws IOException {
        ObjectNode node = (ObjectNode) json.readTree(fixture("calculate.json"));
        ((ObjectNode) node.withArray("payments").get(0)).put("amount", "0.00");
        node.put("fingerprint", "not-a-sha256");

        assertThat(violations(json.treeToValue(node, ForecastSnapshot.class)))
                .contains("payments[0].amount", "fingerprint");
    }

    private Set<String> violations(Object value) {
        return validator.validate(value).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    private <T> T read(String name, Class<T> type) throws IOException {
        try (InputStream input = fixture(name)) {
            return json.readValue(input, type);
        }
    }

    private InputStream fixture(String name) {
        InputStream input = getClass().getResourceAsStream("/fixtures/" + name);
        assertThat(input).as("fixture %s", name).isNotNull();
        return input;
    }
}
