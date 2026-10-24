package ru.cashflow.forecast.api.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.cashflow.forecast.api.dto.request.BacktestSnapshot;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.api.dto.request.RecurringDetectionInput;
import ru.cashflow.forecast.api.dto.response.BacktestRs;
import ru.cashflow.forecast.api.dto.response.ForecastRs;
import ru.cashflow.forecast.api.dto.response.RecurringDetectionResult;

@RequestMapping("/internal/v1/forecast")
public interface ForecastController {
    @PostMapping(value = "/calculate", consumes = "application/json", produces = "application/json")
    ForecastRs calculate(@RequestBody @Valid ForecastSnapshot snapshot,
            @RequestHeader("X-Calculation-Id") UUID calculationId, @RequestHeader("X-Job-Id") UUID jobId,
            @RequestHeader("Idempotency-Key") UUID key);

    @PostMapping(value = "/backtest", consumes = "application/json", produces = "application/json")
    BacktestRs backtest(@RequestBody @Valid BacktestSnapshot snapshot,
            @RequestHeader("X-Calculation-Id") UUID calculationId, @RequestHeader("X-Job-Id") UUID jobId,
            @RequestHeader("Idempotency-Key") UUID key);

    @PostMapping(value = "/recurring-detection", consumes = "application/json", produces = "application/json")
    RecurringDetectionResult detect(@RequestBody @Valid RecurringDetectionInput input,
            @RequestHeader("X-Calculation-Id") UUID calculationId, @RequestHeader("X-Job-Id") UUID jobId,
            @RequestHeader("Idempotency-Key") UUID key);
}
