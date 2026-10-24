package ru.cashflow.orchestrator.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/** In-process stand-in for ForecastService that records every request; responses are scripted by the test. */
public final class FakeForecastServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<Recorded, Reply>> responder =
            new AtomicReference<>(r -> new Reply(503, "{}", Map.of()));

    public FakeForecastServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                var recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                requests.add(recorded);
                Reply reply = responder.get().apply(recorded);
                reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(Function<Recorded, Reply> function) {
        responder.set(function);
    }

    public List<Recorded> requests() {
        return requests;
    }

    public void reset() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Recorded(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    public record Reply(int status, String body, Map<String, String> headers) {
    }
}
