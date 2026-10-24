package ru.cashflow.forecast.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import ru.cashflow.forecast.api.dto.request.CalibrationOriginSnapshot;
import ru.cashflow.forecast.api.dto.request.BacktestSnapshot;
import ru.cashflow.forecast.api.dto.request.RecurringDetectionInput;
import ru.cashflow.forecast.features.backtest.model.BacktestInput;
import ru.cashflow.forecast.features.recurring.model.DetectionInput;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.risk.model.CalibrationOrigin;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ForecastMapper {
    ForecastInput toPointInput(ForecastSnapshot input);

    BacktestInput toBacktestInput(BacktestSnapshot snapshot);

    DetectionInput toDetectionInput(RecurringDetectionInput input);

    @Mapping(target = "fingerprint", source = "input.fingerprint")
    CalibrationOrigin toOrigin(CalibrationOriginSnapshot origin);

    default CalculationInput toCalculationInput(ForecastSnapshot snapshot) {
        return new CalculationInput(toPointInput(snapshot), snapshot.fingerprint(),
                snapshot.calibrationOrigins().stream().map(this::toOrigin).toList());
    }
}
