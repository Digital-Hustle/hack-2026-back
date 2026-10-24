package ru.cashflow.forecast.units;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.cashflow.forecast.api.filter.CallerAllowlist;
import ru.cashflow.forecast.api.filter.InternalRequestFilter;
import ru.cashflow.forecast.config.ForecastProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalRequestFilterTest {
    private final ForecastProperties limits = new ForecastProperties(1024, 1, 10, 100, 2, 3000, List.of("127.0.0.1/32", "::1/128"));

    @Test
    void rejectsOversizedDeclaredAndChunkedBodiesBeforeParsing() throws Exception {
        var filter = new InternalRequestFilter(TestInputs.JSON, limits);
        var request = request(" ".repeat(1025));
        assertRejected(filter, request, 413);
        var chunked = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() { return -1; }

            @Override
            public int getContentLength() { return -1; }
        };
        setup(chunked, " ".repeat(1025));
        assertRejected(filter, chunked, 413);
    }

    @Test
    void neverUsesForwardedHeadersToAllowAnUntrustedPeer() throws Exception {
        var request = request("{}");
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("Forwarded", "for=127.0.0.1");
        assertRejected(new InternalRequestFilter(TestInputs.JSON, limits), request, 403);
    }

    @Test
    void boundedConcurrencyRejectsOverloadAndReleasesPermitAfterFailure() throws Exception {
        var filter = new InternalRequestFilter(TestInputs.JSON, limits);
        var response = new MockHttpServletResponse();
        var innerResponse = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request("{}"), response, (req, rs) -> {
            filter.doFilter(request("{}"), innerResponse, (inner, ignored) -> { });
            throw new jakarta.servlet.ServletException("test chain failure");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(innerResponse.getStatus()).isEqualTo(503);
        assertThat(innerResponse.getHeader("Retry-After")).isEqualTo("1");
        AtomicInteger called = new AtomicInteger();
        filter.doFilter(request("{}"), new MockHttpServletResponse(), (req, rs) -> called.incrementAndGet());
        assertThat(called.get()).isEqualTo(1);
    }

    @Test
    void rejectsAggregateArraysAndNonJsonMediaType() throws Exception {
        var filter = new InternalRequestFilter(TestInputs.JSON, limits);
        assertRejected(filter, request("{\"payments\":[" + String.join(",", java.util.Collections.nCopies(11, "{}")) + "]}"), 413);
        var text = request("not json");
        text.setContentType("text/plain");
        assertRejected(filter, text, 415);
    }

    @Test
    void cidrHandlesIpv4Ipv6AndRejectsInvalidConfiguration() {
        var properties = new ForecastProperties(1024, 1, 10, 100, 2, 3000, List.of("10.20.30.0/24", "::1/128"));
        var allowlist = new CallerAllowlist(properties);
        assertThat(allowlist.isAllowed("10.20.30.1")).isTrue();
        assertThat(allowlist.isAllowed("10.20.31.1")).isFalse();
        assertThat(allowlist.isAllowed("::1")).isTrue();
        assertThat(allowlist.isAllowed("::2")).isFalse();
        assertThatThrownBy(() -> new CallerAllowlist(new ForecastProperties(1024, 1, 10, 100, 2, 3000, List.of("hostname/99"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejected(InternalRequestFilter filter, MockHttpServletRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        filter.doFilter(request, response, (req, rs) -> calls.incrementAndGet());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(calls.get()).isZero();
        assertThat(response.getContentType()).contains("application/problem+json");
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        setup(request, body);
        return request;
    }

    private void setup(MockHttpServletRequest request, String body) {
        request.setMethod("POST");
        request.setRequestURI("/internal/v1/forecast/calculate");
        request.setRemoteAddr("127.0.0.1");
        request.setContentType("application/json");
        request.addHeader("X-Calculation-Id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        request.addHeader("X-Job-Id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        request.addHeader("Idempotency-Key", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
    }
}
