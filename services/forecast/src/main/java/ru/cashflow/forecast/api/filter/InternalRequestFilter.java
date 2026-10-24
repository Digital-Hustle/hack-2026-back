package ru.cashflow.forecast.api.filter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.cashflow.forecast.api.error.Problems;
import ru.cashflow.forecast.config.ForecastProperties;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalRequestFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final ForecastProperties properties;
    private final CallerAllowlist callers;
    private final Semaphore capacity;
    private static final Logger LOG = LoggerFactory.getLogger(InternalRequestFilter.class);

    public InternalRequestFilter(ObjectMapper json, ForecastProperties properties) {
        this.json = json;
        this.properties = properties;
        callers = new CallerAllowlist(properties);
        capacity = new Semaphore(properties.maxConcurrentRequests());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            handle(request, response, chain);
        } finally {
            LOG.info("forecast_request traceId={} status={} durationMs={}", request.getAttribute("forecast.traceId"),
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute("forecast.traceId", traceId);
        response.setHeader("X-Request-Id", traceId);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getRequestURI().startsWith("/internal/v1/forecast")) {
            if (!callers.isAllowed(request.getRemoteAddr())) {
                write(response, 403, "FORBIDDEN", "Caller is not allowed on the internal interface", traceId);
                return;
            }
            if (request.getHeader("Authorization") != null || request.getHeader("Cookie") != null) {
                write(response, 403, "FORBIDDEN", "User credentials must not be sent to Forecast", traceId);
                return;
            }
            for (String header : List.of("Idempotency-Key", "X-Job-Id", "X-Calculation-Id")) {
                String value = request.getHeader(header);
                if (value == null || !value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                        || java.util.Collections.list(request.getHeaders(header)).size() != 1) {
                    write(response, 400, "MALFORMED_REQUEST", "Required UUID headers are missing or invalid", traceId);
                    return;
                }
            }
            if (!request.getHeader("Idempotency-Key").equalsIgnoreCase(request.getHeader("X-Job-Id"))) {
                write(response, 400, "MALFORMED_REQUEST", "Job and idempotency identities must match", traceId);
                return;
            }
            try {
                if (request.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
                    write(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json", traceId);
                    return;
                }
            } catch (org.springframework.http.InvalidMediaTypeException exception) {
                write(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json", traceId);
                return;
            }
            if (request.getContentLengthLong() > properties.maxRequestBytes()) {
                write(response, 413, "PAYLOAD_TOO_LARGE", "Request exceeds the byte limit", traceId);
                return;
            }
            if (!capacity.tryAcquire()) {
                response.setHeader("Retry-After", "1");
                write(response, 503, "DEPENDENCY_UNAVAILABLE", "Calculation capacity is exhausted", traceId);
                return;
            }
            try {
                byte[] bytes = request.getInputStream().readNBytes(properties.maxRequestBytes() + 1);
                if (bytes.length > properties.maxRequestBytes()) {
                    write(response, 413, "PAYLOAD_TOO_LARGE", "Request exceeds the byte limit", traceId);
                    return;
                }
                JsonNode tree;
                try {
                    tree = json.readTree(bytes);
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    write(response, 400, "MALFORMED_REQUEST", "Invalid JSON syntax", traceId);
                    return;
                }
                if (tree == null) {
                    write(response, 400, "MALFORMED_REQUEST", "JSON body is required", traceId);
                    return;
                }
                long[] totals = new long[4];
                count(tree, totals);
                if (totals[0] > properties.maxTotalPayments() || totals[1] > properties.maxTotalDays()
                        || totals[2] > properties.maxSnapshots() || totals[3] > properties.maxScenarioCells()) {
                    write(response, 413, "PAYLOAD_TOO_LARGE", "Request exceeds the aggregate calculation limit", traceId);
                    return;
                }
                chain.doFilter(new CachedBodyRequest(request, bytes), response);
            } finally {
                capacity.release();
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, int status, String code, String detail, String traceId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), Problems.create(status, code, detail, List.of(), traceId));
    }

    private void count(JsonNode node, long[] totals) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String field = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isArray()) {
                    if (field.equals("payments") || field.equals("actualPayments")) {
                        totals[0] += value.size();
                    } else if (field.equals("dailyHistory") || field.equals("actualDays")) {
                        totals[1] += value.size();
                    }
                }
                count(value, totals);
            });
            if (node.has("dailyHistory") && node.has("asOfDate")) {
                totals[2]++;
                if (node.path("calibrationOrigins").size() > 0) {
                    totals[3] += 30L * Math.max(0, Math.min(1_000_000L, node.path("settings").path("scenarioCount").asLong()));
                }
            }
        } else if (node.isArray()) {
            node.forEach(value -> count(value, totals));
        }
    }
}
