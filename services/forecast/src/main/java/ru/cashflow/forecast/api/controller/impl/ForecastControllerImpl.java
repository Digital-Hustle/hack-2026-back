package ru.cashflow.forecast.api.controller.impl;

import java.time.Clock;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;
import ru.cashflow.forecast.api.controller.ForecastController;
import ru.cashflow.forecast.api.dto.request.BacktestSnapshot;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.api.dto.request.RecurringDetectionInput;
import ru.cashflow.forecast.api.dto.response.BacktestRs;
import ru.cashflow.forecast.api.dto.response.ForecastRs;
import ru.cashflow.forecast.api.dto.response.RecurringDetectionResult;
import ru.cashflow.forecast.api.mapper.ForecastMapper;
import ru.cashflow.forecast.api.mapper.ResponseMapper;
import ru.cashflow.forecast.api.validation.WireSnapshotValidator;
import ru.cashflow.forecast.features.backtest.application.BacktestService;
import ru.cashflow.forecast.features.forecast.application.ForecastService;
import ru.cashflow.forecast.features.recurring.application.RecurringDetector;

@RestController
public class ForecastControllerImpl implements ForecastController {
    private final ForecastMapper inputs;
    private final ResponseMapper responses;
    private final ForecastService forecasts;
    private final BacktestService backtests;
    private final RecurringDetector detector;
    private final Clock clock;
    private final WireSnapshotValidator validator;

    public ForecastControllerImpl(ForecastMapper inputs, ResponseMapper responses, ForecastService forecasts,
            BacktestService backtests, RecurringDetector detector, Clock clock, WireSnapshotValidator validator) {
        this.inputs = inputs;
        this.responses = responses;
        this.forecasts = forecasts;
        this.backtests = backtests;
        this.detector = detector;
        this.clock = clock;
        this.validator = validator;
    }

    @Override
    public ForecastRs calculate(ForecastSnapshot snapshot, UUID calculationId, UUID jobId, UUID key) {
        validator.validate(snapshot);
        return responses.toForecast(forecasts.calculate(inputs.toCalculationInput(snapshot)), calculationId, jobId);
    }

    @Override
    public BacktestRs backtest(BacktestSnapshot snapshot, UUID calculationId, UUID jobId, UUID key) {
        snapshot.originSnapshots().forEach(validator::validate);
        var input = inputs.toBacktestInput(snapshot);
        return responses.toBacktest(backtests.evaluate(input), input, calculationId, jobId, clock.instant());
    }

    @Override
    public RecurringDetectionResult detect(RecurringDetectionInput input, UUID calculationId, UUID jobId, UUID key) {
        return responses.toDetection(detector.detect(inputs.toDetectionInput(input)));
    }
}
