package ru.cashflow.forecast.units;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.api.json.ForecastJson;
import ru.cashflow.forecast.api.json.SnapshotFingerprint;
import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {
    @Test
    void matchesIndependentPythonVectorAndIgnoresSnapshotIdentityAndArrayOrder() throws Exception {
        var json = ForecastJson.mapper();
        ObjectNode input = (ObjectNode) json.readTree(getClass().getResourceAsStream("/fixtures/calculate.json"));
        assertThat(SnapshotFingerprint.compute(input))
                .isEqualTo("fb2fecf5bb5974cac1376b0491624a1e3761a5835d4da1b6941b76cfe979b7bc");
        ((ObjectNode) input.withArray("knownPlans").get(0)).put("knownAt", "2026-10-20T13:00:00+03:00");
        assertThat(SnapshotFingerprint.compute(input))
                .isEqualTo("fb2fecf5bb5974cac1376b0491624a1e3761a5835d4da1b6941b76cfe979b7bc");
        input.put("snapshotId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        input.put("fingerprint", "f".repeat(64));
        var payments = input.withArray("payments");
        payments.add(payments.remove(0));
        assertThat(SnapshotFingerprint.compute(input))
                .isEqualTo("fb2fecf5bb5974cac1376b0491624a1e3761a5835d4da1b6941b76cfe979b7bc");
        input.put("startBalance", "100001.00");
        assertThat(SnapshotFingerprint.compute(input))
                .isNotEqualTo("fb2fecf5bb5974cac1376b0491624a1e3761a5835d4da1b6941b76cfe979b7bc");
    }

    @Test
    void canonicalizesNestedInputButKeepsScoringOutcomes() throws Exception {
        var json = ForecastJson.mapper();
        ObjectNode origin = (ObjectNode) json.readTree(getClass().getResourceAsStream("/fixtures/calibration-origin.json"));
        assertThat(SnapshotFingerprint.compute(origin.get("input")))
                .isEqualTo("92066d5e4558126c6398e13558e421d81d8df1f6e709542d8f0387178e08301b");
        ObjectNode outer = (ObjectNode) json.readTree(getClass().getResourceAsStream("/fixtures/calculate.json"));
        outer.withArray("calibrationOrigins").add(origin);
        String before = SnapshotFingerprint.compute(outer);
        ((ObjectNode) origin.get("input")).put("snapshotId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(SnapshotFingerprint.compute(outer)).isEqualTo(before);
        ((ObjectNode) origin.withArray("actualDays").get(0)).put("inflow", "1.00");
        assertThat(SnapshotFingerprint.compute(outer)).isNotEqualTo(before);
    }

    @Test
    void normalizesBusinessUuidCaseButKeepsNullsAndExternalStringsInHash() throws Exception {
        ObjectNode input = TestInputs.fixture("calculate.json");
        input.put("accountId", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA");
        String upper = SnapshotFingerprint.compute(input);
        input.put("accountId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(SnapshotFingerprint.compute(input)).isEqualTo(upper);
        var plan = (ObjectNode) input.withArray("knownPlans").get(0);
        plan.remove("note");
        assertThat(SnapshotFingerprint.compute(input)).isNotEqualTo(upper);
    }

    @Test
    void normalizesUuidBeforeArraySortingAndNormalizesNegativeZeroMoney() throws Exception {
        ObjectNode input = TestInputs.fixture("calculate.json");
        var first = (ObjectNode) input.withArray("payments").get(0);
        var second = (ObjectNode) input.withArray("payments").get(1);
        first.put("paymentId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        second.put("paymentId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        String canonical = SnapshotFingerprint.compute(input);
        first.put("paymentId", "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB");
        assertThat(SnapshotFingerprint.compute(input)).isEqualTo(canonical);
        ((ObjectNode) input.get("settings")).put("bufferAmount", "-0.00");
        assertThat(SnapshotFingerprint.compute(input)).isEqualTo(canonical);
    }
}
