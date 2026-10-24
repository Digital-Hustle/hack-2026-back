package ru.cashflow.forecast.features.forecast.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.model.GapEpisode;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.Money;

@Component
public class GapDetector {
    public List<GapEpisode> detect(String identity, PointForecast point, RiskResult risk, BigDecimal buffer) {
        var gaps = new ArrayList<GapEpisode>();
        collect(gaps, identity, "CASH_GAP", Money.ZERO, point, risk);
        if (buffer.signum() > 0) {
            collect(gaps, identity, "BUFFER_BREACH", buffer, point, risk);
        }
        return List.copyOf(gaps);
    }

    private void collect(List<GapEpisode> gaps, String identity, String kind, BigDecimal threshold, PointForecast point, RiskResult risk) {
        int from = -1;
        for (int index = 0; index <= point.days().size(); index++) {
            boolean breach = false;
            if (index < point.days().size()) {
                Double probability = kind.equals("CASH_GAP") ? risk.days().get(index).pBelowZero() : risk.days().get(index).pBelowBuffer();
                breach = point.days().get(index).balance().compareTo(threshold) < 0 || probability != null && probability >= 0.3;
            }
            if (breach && from == -1) {
                from = index;
            } else if (!breach && from != -1) {
                gaps.add(episode(identity, kind, threshold, from, index - 1, point, risk));
                from = -1;
            }
        }
    }

    private GapEpisode episode(String identity, String kind, BigDecimal threshold, int from, int to, PointForecast point, RiskResult risk) {
        BigDecimal minimum = point.days().subList(from, to + 1).stream().map(day -> day.balance()).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal shortfall = Money.cents(threshold.subtract(minimum).max(Money.ZERO));
        var conditionalShortfalls = new ArrayList<BigDecimal>();
        for (var scenario : risk.scenarios()) {
            BigDecimal scenarioMinimum = scenario.subList(from, to + 1).stream().min(BigDecimal::compareTo).orElseThrow();
            if (scenarioMinimum.compareTo(threshold) < 0) {
                conditionalShortfalls.add(Money.cents(threshold.subtract(scenarioMinimum)));
            }
        }
        boolean available = risk.estimate().availability() == RiskAvailability.AVAILABLE;
        Double probability = available ? (double) conditionalShortfalls.size() / risk.scenarios().size() : null;
        var fromDate = point.days().get(from).date();
        var toDate = point.days().get(to).date();
        UUID id = UUID.nameUUIDFromBytes((identity + ":" + kind + ":" + fromDate + ":" + toDate).getBytes(StandardCharsets.UTF_8));
        return new GapEpisode(id, kind, fromDate, toDate, threshold, minimum, shortfall, probability,
                available ? Money.quantile(conditionalShortfalls, new BigDecimal("0.5")) : null,
                available ? Money.quantile(conditionalShortfalls, new BigDecimal("0.9")) : null,
                "CONDITIONAL_ON_BREACH", risk.estimate().availability());
    }
}
