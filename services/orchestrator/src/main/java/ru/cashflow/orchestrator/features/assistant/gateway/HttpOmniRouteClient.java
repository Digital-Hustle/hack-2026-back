package ru.cashflow.orchestrator.features.assistant.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Message;

/** OpenAI Chat Completions-compatible OmniRoute adapter. No credential or payload logging. */
@Component
public class HttpOmniRouteClient implements AiChatClient {

    private static final int MAX_RESPONSE_CHARS = 100_000;
    private static final int MAX_ANSWER_CHARS = 8_000;

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;

    @Autowired
    public HttpOmniRouteClient(
            ObjectMapper json,
            @Value("${orchestrator.ai.base-url:https://omniroute.bratancorp.online/v1}") String baseUrl,
            @Value("${orchestrator.ai.api-key:}") String apiKey,
            @Value("${orchestrator.ai.model:auto}") String model,
            @Value("${orchestrator.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${orchestrator.ai.request-timeout:30s}") Duration requestTimeout) {
        this(json, baseUrl, apiKey, model, connectTimeout, requestTimeout,
                HttpClient.newBuilder().connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    // Package-private constructor for contract tests against an in-process HTTP server.
    HttpOmniRouteClient(ObjectMapper json, String baseUrl, String apiKey, String model,
                        Duration connectTimeout, Duration requestTimeout, HttpClient http) {
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.http = http;
        String root = baseUrl.replaceAll("/+$", "");
        this.endpoint = URI.create(root + "/chat/completions");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("AI URL must use HTTPS or HTTP");
        }
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String complete(List<Message> messages) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AI calls must run outside database transactions");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(503, "AI_NOT_CONFIGURED", "AI-ассистент не настроен");
        }
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                    "model", model,
                    "stream", false,
                    "max_tokens", 800,
                    "messages", messages));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize AI request", exception);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 429) {
                throw new ApiException(503, "AI_RATE_LIMITED", "Лимит запросов AI временно исчерпан");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(502, "AI_UPSTREAM_ERROR", "AI-провайдер не обработал запрос");
            }
            if (response.body() == null || response.body().length() > MAX_RESPONSE_CHARS) {
                throw new ApiException(502, "AI_BAD_RESPONSE", "Некорректный ответ AI-провайдера");
            }
            JsonNode result = json.readTree(response.body());
            JsonNode content = result.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()
                    || content.asText().length() > MAX_ANSWER_CHARS) {
                throw new ApiException(502, "AI_BAD_RESPONSE", "Некорректный ответ AI-провайдера");
            }
            return content.asText();
        } catch (HttpTimeoutException exception) {
            throw new ApiException(504, "AI_TIMEOUT", "AI-провайдер не ответил вовремя");
        } catch (IOException exception) {
            throw new ApiException(503, "AI_UNAVAILABLE", "AI-провайдер недоступен");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "AI_INTERRUPTED", "Вызов AI прерван");
        }
    }
}
