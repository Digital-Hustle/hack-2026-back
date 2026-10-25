package ru.cashflow.orchestrator.api.dto;

import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Message;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Reply;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Question;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Source;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Fact;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Status;

public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record ChatRq(@NotBlank @Size(max = 2000) String message,
                         @Valid @Size(max = 10) List<HistoryItem> history,
                         LocalDate fromDate, LocalDate toDate, UUID forecastId, UUID gapId) {
        public Question question() {
            return new Question(message, historyMessages(), fromDate, toDate, forecastId, gapId);
        }

        public List<Message> historyMessages() {
            return history == null ? List.of()
                    : history.stream().map(item -> item == null ? null
                            : new Message(item.role(), item.content())).toList();
        }
    }

    public record HistoryItem(@NotBlank String role, @NotBlank @Size(max = 2000) String content) {
    }

    public record ChatRs(String answer, String model, String asOfDate, long dataVersion,
                         boolean forecastAvailable, boolean forecastStale, Status status,
                         List<Source> sources, List<Fact> facts, List<String> warnings) {
        public static ChatRs of(Reply reply) {
            return new ChatRs(reply.answer(), reply.model(), reply.asOfDate(),
                    reply.dataVersion(), reply.forecastAvailable(), reply.forecastStale(), reply.status(),
                    reply.sources(), reply.facts(), reply.warnings());
        }
    }
}
