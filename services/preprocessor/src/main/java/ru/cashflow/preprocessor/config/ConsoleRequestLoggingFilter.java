package ru.cashflow.preprocessor.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ConsoleRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleRequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previousId = MDC.get("requestId");
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        boolean preparation = "/internal/v1/statements/prepare".equals(request.getRequestURI());
        try {
            if (preparation) {
                LOG.info("statement_preparation_started requestId={}", requestId);
            }
            chain.doFilter(request, response);
        } finally {
            if (preparation) {
                LOG.info("statement_preparation_completed requestId={} status={} durationMs={}", requestId,
                        response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
            if (previousId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousId);
            }
        }
    }
}
