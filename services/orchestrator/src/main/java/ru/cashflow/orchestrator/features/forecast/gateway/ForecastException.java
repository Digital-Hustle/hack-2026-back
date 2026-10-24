package ru.cashflow.orchestrator.features.forecast.gateway;

/**
 * Classified call failure. RETRYABLE (timeout, connection, 503/429/5xx) may be retried with the same bytes; PERMANENT
 * (400/404/409/422 or an invalid result) must not be repeated, because a bad payload stays bad (handoff §6).
 */
public class ForecastException extends RuntimeException {

    public enum Kind { RETRYABLE, PERMANENT }

    private final Kind kind;
    private final String code;
    private final Integer retryAfterSeconds;

    public ForecastException(Kind kind, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
