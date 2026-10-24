package ru.cashflow.orchestrator.features.assistant.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Context;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Fact;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Period;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Plan;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Status;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Topic;

/** Fail closed: no model-authored prose, numbers, dates, source URLs or extra fields reach the answer. */
@Component
public class AssistantProtocol {
    public static final int MAX_SELECTED_FACTS = 12;
    private final ObjectMapper json;

    public AssistantProtocol(ObjectMapper json) {
        this.json = json;
    }

    public Optional<Plan> plan(String raw) {
        try {
            JsonNode node = read(raw);
            if (!fields(node, Set.of("status", "topics", "period")) || !node.path("topics").isArray()
                    || !node.path("status").isTextual() || !node.path("period").isTextual()) {
                return Optional.empty();
            }
            Status status = Status.valueOf(node.path("status").asText());
            Period period = Period.valueOf(node.path("period").asText());
            List<Topic> topics = new ArrayList<>();
            for (JsonNode topic : node.path("topics")) {
                if (!topic.isTextual() || topics.size() >= Topic.values().length) {
                    return Optional.empty();
                }
                Topic value = Topic.valueOf(topic.asText());
                if (topics.contains(value)) {
                    return Optional.empty();
                }
                topics.add(value);
            }
            if (status == Status.INSUFFICIENT_DATA || (status == Status.ANSWERED && topics.isEmpty())
                    || (status != Status.ANSWERED && !topics.isEmpty())) {
                return Optional.empty();
            }
            return Optional.of(new Plan(status, List.copyOf(topics), period));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Optional<Selection> selection(String raw, Context context) {
        try {
            JsonNode node = read(raw);
            if (!fields(node, Set.of("status", "factIds")) || !node.path("status").isTextual()
                    || !node.path("factIds").isArray()) {
                return Optional.empty();
            }
            Status status = Status.valueOf(node.path("status").asText());
            List<Fact> selected = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode id : node.path("factIds")) {
                if (!id.isTextual() || selected.size() >= MAX_SELECTED_FACTS || !seen.add(id.asText())) {
                    return Optional.empty();
                }
                Optional<Fact> fact = context.facts().stream().filter(f -> f.id().equals(id.asText())).findFirst();
                if (fact.isEmpty()) {
                    return Optional.empty();
                }
                selected.add(fact.get());
            }
            if ((status == Status.ANSWERED && selected.isEmpty())
                    || (status != Status.ANSWERED && !selected.isEmpty())) {
                return Optional.empty();
            }
            return Optional.of(new Selection(status, List.copyOf(selected)));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private JsonNode read(String raw) throws JsonProcessingException {
        return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
    }

    public record Selection(Status status, List<Fact> facts) {
    }
}
