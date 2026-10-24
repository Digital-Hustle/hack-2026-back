package ru.cashflow.orchestrator.features.forecast.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * java.net.http client for ForecastService. Base URL and timeouts come from configuration, not business code (§4).
 * Sends only Content-Type, Idempotency-Key, X-Calculation-Id and X-Job-Id; never Authorization, cookies or X-User-*.
 * Logs only path, status and duration — never request or response bodies.
 */
@Component
public class HttpForecastClient implements ForecastClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpForecastClient.class);
    private static final int MAX_RESPONSE_CHARS = 50_000_000;

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration requestTimeout;

    public HttpForecastClient(@Value("${orchestrator.forecast.base-url:http://forecast:8080}") String baseUrl,
                              @Value("${orchestrator.forecast.connect-timeout:3s}") Duration connectTimeout,
                              @Value("${orchestrator.forecast.request-timeout:15s}") Duration requestTimeout) {
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public ForecastResponse post(String path, String body, UUID jobId, UUID resultId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Forecast must not be called inside a database transaction");
        }
        var request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", jobId.toString())
                .header("X-Job-Id", jobId.toString())
                .header("X-Calculation-Id", resultId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LOG.info("forecast_call path={} status={} durationMs={}", path, response.statusCode(),
                    (System.nanoTime() - start) / 1_000_000);
            String text = response.body();
            if (text != null && text.length() > MAX_RESPONSE_CHARS) {
                throw new ForecastException(ForecastException.Kind.PERMANENT, "RESPONSE_TOO_LARGE",
                        "Ответ Forecast превышает допустимый размер", null);
            }
            Integer retryAfter = response.headers().firstValue("Retry-After").map(HttpForecastClient::seconds)
                    .orElse(null);
            return new ForecastResponse(response.statusCode(), text, retryAfter);
        } catch (HttpTimeoutException exception) {
            LOG.warn("forecast_call_timeout path={}", path);
            throw new ForecastException(ForecastException.Kind.RETRYABLE, "FORECAST_TIMEOUT",
                    "Forecast не ответил вовремя", null);
        } catch (java.io.IOException exception) {
            LOG.warn("forecast_call_io path={}", path);
            throw new ForecastException(ForecastException.Kind.RETRYABLE, "FORECAST_UNAVAILABLE",
                    "Forecast недоступен", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ForecastException(ForecastException.Kind.RETRYABLE, "INTERRUPTED", "Вызов прерван", null);
        }
    }

    private static Integer seconds(String value) {
        try {
            int v = Integer.parseInt(value.strip());
            return v >= 0 && v <= 3600 ? v : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
