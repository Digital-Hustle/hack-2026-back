package ru.cashflow.orchestrator.features.forecast.gateway;

import java.util.UUID;

/**
 * Port to the stateless ForecastService (handoff §4). All three calls are synchronous: 200 with the computed body.
 * Implementations must never be called inside a database transaction and never send JWT/cookies/user headers.
 */
public interface ForecastClient {

    String PATH_CALCULATE = "/internal/v1/forecast/calculate";
    String PATH_BACKTEST = "/internal/v1/forecast/backtest";
    String PATH_RECURRING = "/internal/v1/forecast/recurring-detection";

    /**
     * @param body       exact stored snapshot bytes; every retry sends the same bytes
     * @param jobId      Idempotency-Key and X-Job-Id
     * @param resultId   X-Calculation-Id (for recurring detection the job id, as in §4.4)
     */
    ForecastResponse post(String path, String body, UUID jobId, UUID resultId);

    /** Raw response; the result validator decides what is acceptable (task 10). */
    record ForecastResponse(int status, String body, Integer retryAfterSeconds) {
    }
}
