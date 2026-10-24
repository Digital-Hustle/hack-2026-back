package ru.cashflow.orchestrator.units;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.alerts.command.GapExplanationBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class GapExplanationBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID gapId = UUID.randomUUID();
    private final UUID patternId = UUID.randomUUID();

    @Test
    void confirmedSchedulesAreDisplayedWithoutDuplicatingAReplacementPlanOrChangingSavedInputs() {
        ObjectNode input = json.createObjectNode().put("scope", "WITH_KNOWN_PLANS");
        ObjectNode result = result();
        input.putArray("dailyHistory");
        var pattern = input.putArray("recurringPatterns").addObject();
        pattern.put("id", patternId.toString()).put("status", "CONFIRMED").put("direction", "OUT")
                .put("categoryCode", "RENT").put("amount", "100.00");
        pattern.putObject("schedule").put("frequency", "MONTHLY").putNull("weekday").put("dayOfMonth", 31)
                .put("businessDayRule", "FOLLOWING");
        // 31 October 2026 is Saturday; a FOLLOWING payment appears on Monday 2 November.
        UUID replacementId = UUID.randomUUID();
        input.putArray("knownPlans").addObject().put("id", replacementId.toString()).put("status", "OPEN")
                .put("dueDate", "2026-10-31").put("direction", "OUT").put("remainingAmount", "120.00")
                .put("amount", "120.00").put("categoryCode", "RENT").put("title", "Replacement")
                .put("source", "MANUAL").put("relation", "REPLACES_RECURRING").put("recurringPatternId", patternId.toString());
        String originalInput = input.toString();
        String originalResult = result.toString();

        var details = build(input, result);

        assertThat(details.mandatoryOutflows()).hasSize(1);
        assertThat(details.mandatoryOutflows().getFirst().id()).isEqualTo(replacementId);
        assertThat(details.mandatoryOutflows().getFirst().amount()).isEqualTo("120.00");
        assertThat(input.toString()).isEqualTo(originalInput);
        assertThat(result.toString()).isEqualTo(originalResult);

        input.withArray("knownPlans").removeAll();
        var withoutReplacement = build(input, result);
        assertThat(withoutReplacement.mandatoryOutflows()).hasSize(1);
        assertThat(withoutReplacement.mandatoryOutflows().getFirst().date()).hasToString("2026-11-02");
        assertThat(withoutReplacement.mandatoryOutflows().getFirst().nominalDate()).hasToString("2026-10-31");
    }

    @Test
    void excludedPatternsDoNotBecomeMandatoryAndMissingProbabilityIsNotZero() {
        ObjectNode input = json.createObjectNode().put("scope", "HISTORY_ONLY");
        input.putArray("knownPlans");
        input.putArray("dailyHistory");
        input.putArray("recurringPatterns").addObject().put("status", "EXCLUDED");

        var details = build(input, result());

        assertThat(details.mandatoryOutflows()).isEmpty();
        assertThat(details.expectedInflows()).isEmpty();
        assertThat(details.gap().path("probability").isNull()).isTrue();
        assertThat(details.dataLimitations()).extracting(l -> l.code()).contains("PLANS_NOT_INCLUDED", "RISK_UNAVAILABLE");
        assertThat(details.recommendations()).isEmpty();
    }

    private ru.cashflow.orchestrator.features.alerts.model.GapDetails build(ObjectNode input, ObjectNode result) {
        return GapExplanationBuilder.build(UUID.randomUUID(), UUID.randomUUID(), gapId, input, result, "CURRENT", Instant.EPOCH);
    }

    private ObjectNode result() {
        ObjectNode result = json.createObjectNode().put("asOfDate", "2026-10-30").put("quality", "GOOD")
                .put("inputDataVersion", 1);
        result.putArray("gaps").addObject().put("id", gapId.toString()).put("fromDate", "2026-10-31")
                .put("toDate", "2026-11-02").put("kind", "CASH_GAP").putNull("probability");
        result.putArray("days");
        result.putArray("warnings");
        result.putObject("riskEstimate").put("availability", "INSUFFICIENT_HISTORY");
        return result;
    }
}
