package ru.cashflow.preprocessor.integrations;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Loopback Orchestrator stub: records every request and answers with the contract JSON. Tests change the
 * stage/finalize behaviour between cases; responses echo fileName/fileSha256 from the staging query like the real
 * service does.
 */
final class OrchestratorStub implements AutoCloseable {

    static final UUID IMPORT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final long STAGE_VERSION = 7L;

    private final HttpServer server;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger finalizeCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private volatile int stageStatus = 201;
    private volatile String stageBody;
    private volatile int finalizeStatus = 200;
    private volatile String finalizeBody;
    private volatile String finalizeState = "FINALIZED";
    private volatile String recoveryState = "STAGED";
    private volatile boolean stageAlreadyCommitted;
    private volatile int finalizedRowsInserted = 1;
    private volatile int finalizedRowsAlreadyPresent;
    private volatile String lastFileName;
    private volatile String lastFileSha256;

    private OrchestratorStub(HttpServer server) {
        this.server = server;
    }

    static OrchestratorStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            OrchestratorStub stub = new OrchestratorStub(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return stub;
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось поднять stub Orchestrator", exception);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        requests.clear();
        finalizeCalls.set(0);
        cancelCalls.set(0);
        stageStatus = 201;
        stageBody = null;
        finalizeStatus = 200;
        finalizeBody = null;
        finalizeState = "FINALIZED";
        recoveryState = "STAGED";
        stageAlreadyCommitted = false;
        finalizedRowsInserted = 1;
        finalizedRowsAlreadyPresent = 0;
        lastFileName = null;
        lastFileSha256 = null;
    }

    void stageAlreadyCommitted() {
        stageAlreadyCommitted = true;
    }

    void finalizedRows(int inserted, int alreadyPresent) {
        finalizedRowsInserted = inserted;
        finalizedRowsAlreadyPresent = alreadyPresent;
    }

    void stageProblem(int status, String code, String detail) {
        stageStatus = status;
        stageBody = problem(status, code, detail);
    }

    void stageStructuredProblem() {
        stageStatus = 422;
        stageBody = """
                {"type":"about:blank","status":409,"code":"VALIDATION_FAILED",
                "detail":"Invalid CSV range","traceId":"downstream-trace",
                "errors":[{"field":"accountId","code":"ACCOUNT_INVALID","message":"Unknown account"}],
                "retryAfterSec":7,"resourceId":"11111111-1111-1111-1111-111111111111"}""";
    }

    void finalizeProblem(int status, String code, String detail) {
        finalizeStatus = status;
        finalizeBody = problem(status, code, detail);
    }

    void finalizeReturns(String state) {
        finalizeState = state;
    }

    void recoveryReturns(String state) {
        recoveryState = state;
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    Recorded stageRequest() {
        return requests().stream().filter(request -> request.path().endsWith("/imports")).findFirst().orElseThrow();
    }

    Recorded finalizeRequest() {
        return requests().stream().filter(request -> request.path().endsWith("/finalize")).findFirst().orElseThrow();
    }

    Recorded cancelRequest() {
        return requests().stream().filter(request -> request.path().endsWith("/cancel")).findFirst().orElseThrow();
    }

    int finalizeCalls() {
        return finalizeCalls.get();
    }

    int cancelCalls() {
        return cancelCalls.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(new Recorded(exchange.getRequestMethod(), path, query, headers(exchange), body));
        if (path.endsWith("/finalize")) {
            finalizeCalls.incrementAndGet();
            respond(exchange, finalizeStatus, finalizeBody == null ? importBody(finalizeState, path, STAGE_VERSION + 1,
                    "FINALIZED".equals(finalizeState) ? "2026-01-31T23:59:59Z" : null,
                    "FINALIZED".equals(finalizeState) ? 1L : null) : finalizeBody);
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, importBody(recoveryState, path, STAGE_VERSION + 1,
                    "FINALIZED".equals(recoveryState) ? "2026-01-31T23:59:59Z" : null,
                    null));
            return;
        }
        if (path.endsWith("/cancel")) {
            cancelCalls.incrementAndGet();
            respond(exchange, 200, importBody("ABORTED", path, STAGE_VERSION + 2, null, null));
            return;
        }
        lastFileName = queryParam(query, "fileName");
        lastFileSha256 = queryParam(query, "fileSha256");
        respond(exchange, stageStatus,
                stageBody == null ? importBody(stageAlreadyCommitted ? "FINALIZED" : "STAGED", path, STAGE_VERSION,
                        stageAlreadyCommitted ? "2026-01-31T23:59:59Z" : null, stageAlreadyCommitted ? 1L : null) : stageBody);
    }

    private String importBody(String status, String path, long version, String completedAt, Long dataVersion) {
        String[] segments = path.split("/");
        String companyId = segments[5];
        String accountId = segments[7];
        return """
                {"id":"%s","accountId":"%s","status":"%s","preprocessorStatus":"PREPARED",
                "rowsStaged":1,"rowsInserted":%s,"rowsAlreadyPresent":%d,"issueCount":0,
                "inputFingerprint":"%s","createdAt":"2026-01-01T00:00:00Z",
                "finalizedAt":%s,"version":%d}""".formatted(IMPORT_ID, accountId, status,
                "FINALIZED".equals(status) ? Integer.toString(finalizedRowsInserted) : "null",
                finalizedRowsAlreadyPresent, "a".repeat(64), completedAt == null ? "null" : "\"" + completedAt + "\"", version);
    }

    private static String problem(int status, String code, String detail) {
        return """
                {"type":"about:blank","title":"%s","status":%d,"code":"%s","detail":"%s","traceId":"stub",
                "errors":[],"retryAfterSec":null,"resourceId":null}""".formatted(code, status, code, detail);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> headers(HttpExchange exchange) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> result.put(name, String.join(",", values)));
        return Map.copyOf(result);
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (key.equals(name)) {
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record Recorded(String method, String path, String query, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }

        String queryParam(String name) {
            return OrchestratorStub.queryParam(query, name);
        }
    }
}
