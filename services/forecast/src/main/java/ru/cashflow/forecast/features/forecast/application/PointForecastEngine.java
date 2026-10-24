package ru.cashflow.forecast.features.forecast.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.forecast.model.PointDay;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.Warning;

@Component
public class PointForecastEngine {
    private final SnapshotValidator validator;
    private final HistoryPreparer preparer;
    private final ResidualExtractor extractor;
    private final PlanResolver resolver;

    public PointForecastEngine(SnapshotValidator validator, HistoryPreparer preparer,
            ResidualExtractor extractor, PlanResolver resolver) {
        this.validator = validator;
        this.preparer = preparer;
        this.extractor = extractor;
        this.resolver = resolver;
    }

    public PointForecast calculate(ForecastInput input) {
        validator.validate(input);
        var history = preparer.prepare(input);
        var unplanned = extractor.extract(input, history.days(), history.payments(), Set.of(), false);
        var calendar = resolver.resolve(input, history, unplanned);
        var residuals = calendar.baselineReplacements().isEmpty() ? unplanned
                : extractor.extract(input, history.days(), history.payments(), calendar.baselineReplacements(), false);
        var sums = new EnumMap<DayOfWeek, Flows>(DayOfWeek.class);
        residuals.days().forEach((date, flows) -> {
            Flows current = sums.getOrDefault(date.getDayOfWeek(), Flows.ZERO);
            sums.put(date.getDayOfWeek(), new Flows(current.inflow().add(flows.inflow()), current.outflow().add(flows.outflow())));
        });
        var means = new EnumMap<DayOfWeek, Flows>(DayOfWeek.class);
        for (var weekday : DayOfWeek.values()) {
            Flows sum = sums.getOrDefault(weekday, Flows.ZERO);
            int count = history.sampleCounts().get(weekday);
            means.put(weekday, new Flows(Money.mean(sum.inflow(), count), Money.mean(sum.outflow(), count)));
        }
        var warnings = new ArrayList<>(history.warnings());
        warnings.addAll(calendar.warnings());
        if (input.startBalance().signum() < 0) {
            warnings.add(new Warning("INITIAL_CASH_GAP", "Negative balance already exists at cutoff", input.asOfDate()));
        }
        var days = new ArrayList<PointDay>();
        BigDecimal balance = input.startBalance();
        BigDecimal minimum = null;
        LocalDate firstGap = null;
        for (int horizon = 1; horizon <= 30; horizon++) {
            LocalDate date = input.asOfDate().plusDays(horizon);
            Flows planned = calendar.planned().getOrDefault(date, Flows.ZERO);
            Flows recurring = calendar.recurring().getOrDefault(date, Flows.ZERO);
            Flows residual = means.get(date.getDayOfWeek());
            BigDecimal inflow = Money.cents(planned.inflow().add(recurring.inflow()).add(residual.inflow()));
            BigDecimal outflow = Money.cents(planned.outflow().add(recurring.outflow()).add(residual.outflow()));
            balance = Money.cents(balance.add(inflow).subtract(outflow));
            minimum = minimum == null ? balance : minimum.min(balance);
            if (firstGap == null && balance.signum() < 0) {
                firstGap = date;
            }
            days.add(new PointDay(date, planned.inflow(), planned.outflow(), recurring.inflow(), recurring.outflow(), residual.inflow(), residual.outflow(), inflow, outflow, balance));
        }
        return new PointForecast(days, history.quality(), warnings, calendar.baselineReplacements(), minimum,
                firstGap, minimum.negate().max(Money.ZERO));
    }
}
