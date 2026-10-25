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
import ru.cashflow.forecast.features.shared.model.Algorithm;
import ru.cashflow.forecast.features.shared.model.CalcTrace;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.Warning;

@Component
public class PointForecastEngine {
    private final SnapshotValidator validator;
    private final HistoryPreparer preparer;
    private final ResidualExtractor extractor;
    private final PlanResolver resolver;
    private final RuHolidayCalendar holidays = new RuHolidayCalendar();
    private final RobustWeekdayEstimator robust = new RobustWeekdayEstimator(holidays);

    public PointForecastEngine(SnapshotValidator validator, HistoryPreparer preparer,
            ResidualExtractor extractor, PlanResolver resolver) {
        this.validator = validator;
        this.preparer = preparer;
        this.extractor = extractor;
        this.resolver = resolver;
    }

    public PointForecast calculate(ForecastInput input) {
        return calculate(input, CalcTrace.off());
    }

    public PointForecast calculate(ForecastInput input, CalcTrace trace) {
        validator.validate(input);
        trace.step("VALIDATE", "Snapshot accepted", CalcTrace.data(
                "asOfDate", input.asOfDate().toString(), "algorithm", input.settings().algorithm().name(),
                "scope", input.scope().name(), "startBalance", input.startBalance().toPlainString(),
                "dailyHistoryDays", String.valueOf(input.dailyHistory().size()),
                "payments", String.valueOf(input.payments().size()),
                "knownPlans", String.valueOf(input.knownPlans().size()),
                "recurringPatterns", String.valueOf(input.recurringPatterns().size())));
        var history = preparer.prepare(input);
        trace.step("TRAINING_WINDOW", "Last " + input.settings().windowWeeks() + " weeks before cutoff", CalcTrace.data(
                "days", String.valueOf(history.days().size()), "payments", String.valueOf(history.payments().size()),
                "completeDaysPerWeekday", history.sampleCounts().toString(), "quality", history.quality().name(),
                "warnings", String.valueOf(history.warnings().size())));
        var unplanned = extractor.extract(input, history.days(), history.payments(), Set.of(), false);
        trace.step("RECURRING_EXCLUDED", "Payments explained by confirmed recurring calendar are removed from the residual",
                CalcTrace.data("excludedPayments", String.valueOf(unplanned.excludedPaymentIds().size()),
                        "confirmedPatterns", String.valueOf(input.recurringPatterns().size())));
        var calendar = resolver.resolve(input, history, unplanned);
        var residuals = calendar.baselineReplacements().isEmpty() ? unplanned
                : extractor.extract(input, history.days(), history.payments(), calendar.baselineReplacements(), false);
        trace.step("PLANS", "Known plans and recurring payments placed on the horizon", CalcTrace.data(
                "plannedDates", String.valueOf(calendar.planned().size()),
                "recurringDates", String.valueOf(calendar.recurring().size()),
                "baselineReplacements", String.valueOf(calendar.baselineReplacements().size())));
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
        boolean robustModel = input.settings().algorithm() == Algorithm.WEEKDAY_ROBUST_V2;
        var level = robustModel ? robust.estimate(residuals.days(), means, trace) : means;
        if (trace.enabled() && !robustModel) {
            var data = new java.util.LinkedHashMap<String, String>();
            for (var weekday : DayOfWeek.values()) {
                data.put(weekday.name(), "n=" + history.sampleCounts().get(weekday)
                        + " in=" + means.get(weekday).inflow().toPlainString()
                        + " out=" + means.get(weekday).outflow().toPlainString());
            }
            trace.step("RESIDUAL_LEVEL", "Mean residual flow per weekday (complete days only, zero days included)", data);
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
            boolean closed = robustModel && holidays.isNonWorkingWeekday(date);
            Flows residual = closed ? Flows.ZERO : level.get(date.getDayOfWeek());
            BigDecimal inflow = Money.cents(planned.inflow().add(recurring.inflow()).add(residual.inflow()));
            BigDecimal outflow = Money.cents(planned.outflow().add(recurring.outflow()).add(residual.outflow()));
            balance = Money.cents(balance.add(inflow).subtract(outflow));
            minimum = minimum == null ? balance : minimum.min(balance);
            if (firstGap == null && balance.signum() < 0) {
                firstGap = date;
            }
            days.add(new PointDay(date, planned.inflow(), planned.outflow(), recurring.inflow(), recurring.outflow(), residual.inflow(), residual.outflow(), inflow, outflow, balance));
            if (trace.enabled()) {
                trace.step("DAY", "B(" + date + ") = B(prev) + in - out" + (closed ? " [non-working day: residual = 0]" : ""),
                        CalcTrace.data("weekday", date.getDayOfWeek().name(), "plannedIn", planned.inflow().toPlainString(),
                                "plannedOut", planned.outflow().toPlainString(), "recurringIn", recurring.inflow().toPlainString(),
                                "recurringOut", recurring.outflow().toPlainString(), "residualIn", residual.inflow().toPlainString(),
                                "residualOut", residual.outflow().toPlainString(), "inflow", inflow.toPlainString(),
                                "outflow", outflow.toPlainString(), "balance", balance.toPlainString()));
            }
        }
        trace.step("POINT_RESULT", "30-day point forecast ready", CalcTrace.data(
                "minimumBalance", minimum.toPlainString(), "firstGapDate", firstGap == null ? "none" : firstGap.toString(),
                "maxShortfall", minimum.negate().max(Money.ZERO).toPlainString(), "warnings", String.valueOf(warnings.size())));
        return new PointForecast(days, history.quality(), warnings, calendar.baselineReplacements(), minimum,
                firstGap, minimum.negate().max(Money.ZERO));
    }
}
