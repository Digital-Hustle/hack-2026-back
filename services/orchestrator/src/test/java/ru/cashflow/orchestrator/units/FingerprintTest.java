package ru.cashflow.orchestrator.units;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ignoresSnapshotIdFingerprintAndKeyOrderButNotBusinessIds() throws IOException {
        ObjectNode fixture = (ObjectNode) fixture("calculate.json");
        String base = Fingerprint.of(fixture);

        assertThat(base).matches("^[0-9a-f]{64}$");
        ObjectNode otherId = fixture.deepCopy();
        otherId.put("snapshotId", "99999999-9999-4999-8999-999999999999");
        otherId.put("fingerprint", "deadbeef");
        assertThat(Fingerprint.of(otherId)).isEqualTo(base);

        ObjectNode reordered = json.createObjectNode();
        java.util.List<String> names = new java.util.ArrayList<>();
        fixture.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        names.forEach(n -> reordered.set(n, fixture.get(n)));
        assertThat(Fingerprint.of(reordered)).isEqualTo(base);

        ObjectNode changedPayment = fixture.deepCopy();
        ((ObjectNode) changedPayment.withArray("payments").get(0)).put("paymentId",
                "70000000-0000-4000-8000-000000000099");
        assertThat(Fingerprint.of(changedPayment)).isNotEqualTo(base);

        ObjectNode changedVersion = fixture.deepCopy();
        changedVersion.put("inputDataVersion", 43);
        assertThat(Fingerprint.of(changedVersion)).isNotEqualTo(base);
    }

    @Test
    void canonicalJsonIsCompactSortedAndKeepsNullsAndEmptyLists() throws IOException {
        String canonical = Fingerprint.canonical(fixture("calculate.json"));

        assertThat(canonical).doesNotContain("\n").doesNotContain(": ").doesNotContain("snapshotId")
                .doesNotContain("\"fingerprint\"");
        assertThat(canonical).startsWith("{\"accountId\":");
        assertThat(canonical).contains("\"calibrationOrigins\":[]").contains("\"note\":null")
                .contains("\"recurringPatterns\":[]");
    }

    @Test
    void nestedCalibrationInputDropsItsOwnSnapshotIdAndFingerprint() throws IOException {
        ObjectNode outer = (ObjectNode) fixture("calculate.json");
        ObjectNode origin = (ObjectNode) fixture("calibration-origin.json");
        outer.withArray("calibrationOrigins").add(origin);
        String withOrigin = Fingerprint.of(outer);

        ObjectNode renamed = outer.deepCopy();
        ((ObjectNode) renamed.withArray("calibrationOrigins").get(0).get("input")).put("snapshotId",
                "12121212-1212-4212-8212-121212121212");
        assertThat(Fingerprint.of(renamed)).isEqualTo(withOrigin);
        assertThat(Fingerprint.canonical(outer)).doesNotContain("44444444-4444-4444-8444-444444444444");
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/forecast-examples/" + name)) {
            return json.readTree(in);
        }
    }
}
