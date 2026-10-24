package ru.cashflow.preprocessor.integrations.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/** Structured downstream Problem; the HTTP status, never JSON status, is authoritative. */
public class OrchestratorProblemException extends RuntimeException {
    private final int status;
    private final String code;
    private final JsonNode details;

    public OrchestratorProblemException(int status, String code, String detail) {
        this(status, code, detail, null);
    }

    public OrchestratorProblemException(int status, String code, String detail, JsonNode details) {
        super(detail);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public JsonNode details() {
        return details;
    }
}
