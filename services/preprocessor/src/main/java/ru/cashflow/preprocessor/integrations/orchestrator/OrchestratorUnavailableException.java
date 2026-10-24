package ru.cashflow.preprocessor.integrations.orchestrator;

/** Orchestrator is unreachable, timed out or returned an unusable response. */
public class OrchestratorUnavailableException extends RuntimeException {

    public OrchestratorUnavailableException(String message) {
        super(message);
    }

    public OrchestratorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
