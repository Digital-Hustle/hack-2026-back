package ru.cashflow.orchestrator.units;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.assistant.command.AssistantProtocol;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Context;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Fact;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Metadata;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Status;

class AssistantProtocolTest {
    private final AssistantProtocol protocol = new AssistantProtocol(new ObjectMapper());
    private final Context context = new Context(new Metadata(LocalDate.of(2026, 10, 24), 7), true, false,
            List.of(), List.of(new Fact("f1", "s1", "Остаток: 123456789012345.67 RUB.")), List.of());

    @Test
    void onlySelectsExistingServerFactsAndPreservesTheirExactValues() {
        var result = protocol.selection("{\"status\":\"ANSWERED\",\"factIds\":[\"f1\"]}", context).orElseThrow();
        assertThat(result.facts()).containsExactlyElementsOf(context.facts());
        assertThat(result.facts().getFirst().text()).contains("123456789012345.67");
    }

    @Test
    void rejectsInventedSourcesNumbersProseDuplicateIdsAndConflictingStatuses() {
        for (String output : List.of(
                "Остаток 999999 рублей",
                "{\"status\":\"ANSWERED\",\"factIds\":[\"f999\"]}",
                "{\"status\":\"ANSWERED\",\"factIds\":[\"f1\"],\"answer\":\"999999\"}",
                "{\"status\":\"ANSWERED\",\"factIds\":[\"f1\"],\"source\":\"https://evil.test\"}",
                "{\"status\":\"ANSWERED\",\"factIds\":[\"f1\",\"f1\"]}",
                "{\"status\":\"ANSWERED\",\"factIds\":[\"f1\"],\"factIds\":[\"f1\"]}",
                "{\"status\":\"ANSWERED\",\"factIds\":[\"f1\"]} invented text",
                "{\"status\":\"ANSWERED\",\"factIds\":[]}",
                "{\"status\":\"OUT_OF_SCOPE\",\"factIds\":[\"f1\"]}",
                "{\"status\":\"ANSWERED\",\"factIds\":[1]}", "null")) {
            assertThat(protocol.selection(output, context)).as(output).isEmpty();
        }
        assertThat(protocol.selection("{\"status\":\"INSUFFICIENT_DATA\",\"factIds\":[]}", context)
                .orElseThrow().status()).isEqualTo(Status.INSUFFICIENT_DATA);
    }

    @Test
    void plannerCannotChooseArbitraryToolsPeriodsOrTenantIdentifiers() {
        assertThat(protocol.plan("{\"status\":\"ANSWERED\",\"topics\":[\"CATEGORIES\",\"QUALITY\"],"
                + "\"period\":\"PREVIOUS_MONTH\"}")).isPresent();
        for (String output : List.of(
                "{\"status\":\"ANSWERED\",\"topics\":[\"SQL\"],\"period\":\"LAST_30_DAYS\"}",
                "{\"status\":\"ANSWERED\",\"topics\":[\"FORECAST\"],\"period\":\"2099-01-01\"}",
                "{\"status\":\"ANSWERED\",\"topics\":[\"FORECAST\"],\"period\":\"LAST_30_DAYS\",\"companyId\":\"foreign\"}",
                "{\"status\":\"ANSWERED\",\"topics\":[],\"period\":\"LAST_30_DAYS\"}",
                "{\"status\":\"OUT_OF_SCOPE\",\"topics\":[\"FORECAST\"],\"period\":\"LAST_30_DAYS\"}")) {
            assertThat(protocol.plan(output)).as(output).isEmpty();
        }
    }
}
