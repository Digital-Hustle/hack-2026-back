package ru.cashflow.forecast.api.error;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ru.cashflow.forecast.api.dto.request.BacktestRq;
import ru.cashflow.forecast.api.dto.request.BacktestSnapshot;
import ru.cashflow.forecast.api.dto.request.CalibrationOriginSnapshot;
import ru.cashflow.forecast.api.dto.request.CompletenessRangeRs;
import ru.cashflow.forecast.api.dto.request.DailyActualRs;
import ru.cashflow.forecast.api.dto.request.ExpectedPaymentRs;
import ru.cashflow.forecast.api.dto.request.ForecastSettings;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.api.dto.request.RecurringDetectionInput;
import ru.cashflow.forecast.api.dto.request.RecurringPatternRs;
import ru.cashflow.forecast.api.dto.request.Schedule;
import ru.cashflow.forecast.api.dto.request.SnapshotPayment;
import ru.cashflow.forecast.api.dto.request.SourceDocument;

/** Only known contract properties and bounded array indices are suitable for logs. */
final class DiagnosticFields {
    private static final Set<String> CONTRACT_FIELDS = Stream.concat(Stream.of("body", "field"),
            Stream.of(BacktestRq.class, BacktestSnapshot.class, CalibrationOriginSnapshot.class, CompletenessRangeRs.class,
                    DailyActualRs.class, ExpectedPaymentRs.class, ForecastSettings.class, ForecastSnapshot.class,
                    RecurringDetectionInput.class, RecurringPatternRs.class, Schedule.class, SnapshotPayment.class, SourceDocument.class)
                    .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                    .map(java.lang.reflect.RecordComponent::getName))
            .collect(Collectors.toUnmodifiableSet());

    private DiagnosticFields() { }

    static String safe(String field) {
        if (field == null || field.length() > 256) {
            return null;
        }
        String normalized = field.replace(".[", "[");
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_]*(?:\\[[0-9]{1,6}\\])?(?:\\.[A-Za-z][A-Za-z0-9_]*(?:\\[[0-9]{1,6}\\])?)*")) {
            return null;
        }
        String[] properties = normalized.replaceAll("\\[[0-9]+\\]", "").split("\\.");
        return properties.length <= 12 && Arrays.stream(properties).allMatch(CONTRACT_FIELDS::contains)
                ? normalized : null;
    }
}
