package ru.cashflow.orchestrator.features.assistant.gateway;

import java.util.List;

import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Message;

public interface AiChatClient {

    String complete(List<Message> messages);

    String model();
}
