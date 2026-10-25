package ru.cashflow.orchestrator.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.CostTarget;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Effect;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.ScenarioDay;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Summary;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Type;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioService.ChangeSpec;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioService.MaxPaymentInfo;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioService.Preview;

public final class ScenarioDtos {

    private ScenarioDtos() {
    }

    /**
     * One lever of a scenario. Which fields matter depends on {@code type}:
     * DELAY_RECEIPT = planId or (amount + date), delayDays; REVENUE_DROP = percent, optional fromDate;
     * UNPLANNED_EXPENSE = amount, date; FIXED_COSTS_GROWTH = percent, optional fromDate, optional target.
     */
    public record ChangeRq(@NotNull Type type, @Size(max = 200) String title, UUID planId,
                           @Digits(integer = 17, fraction = 2) BigDecimal amount, LocalDate date, Integer delayDays,
                           @Digits(integer = 3, fraction = 2) BigDecimal percent, LocalDate fromDate,
                           CostTarget target) {
        public ChangeSpec toSpec() {
            return new ChangeSpec(type, title, planId, amount, date, delayDays, percent, fromDate, target);
        }
    }

    /** Stateless: nothing is stored. forecastId is optional, the latest forecast of the account is the default. */
    public record ScenarioRq(UUID forecastId, @NotEmpty @Size(max = 10) List<@Valid ChangeRq> changes) {
    }

    /** date: day of the payment; keepBuffer: stay above bufferAmount instead of zero; changes: optional scenario. */
    public record MaxPaymentRq(UUID forecastId, @NotNull LocalDate date, Boolean keepBuffer,
                               @Size(max = 10) List<@Valid ChangeRq> changes) {
    }

    public record LimitRs(String maxAmount, LocalDate limitingDate, String limitingBalance) {
    }

    public record MaxPaymentRs(BaseForecastRs baseForecast, LocalDate date, boolean keepBuffer, String floor,
                               LimitRs base, LimitRs scenario, List<EffectRs> changes, List<String> notes) {
        public static MaxPaymentRs of(MaxPaymentInfo i) {
            var b = i.baseForecast();
            return new MaxPaymentRs(new BaseForecastRs(b.id(), b.asOfDate(), b.scope(), b.staleness(), b.createdAt(),
                    m(b.startBalance()), m(b.buffer())), i.date(), i.keepBuffer(), m(i.floor()),
                    new LimitRs(m(i.base().amount()), i.base().limitingDate(), m(i.base().limitingBalance())),
                    new LimitRs(m(i.scenario().amount()), i.scenario().limitingDate(),
                            m(i.scenario().limitingBalance())),
                    i.changes().stream().map(EffectRs::of).toList(), i.notes());
        }
    }

    public record BaseForecastRs(UUID id, LocalDate asOfDate, String scope, String staleness, String createdAt,
                                 String startBalance, String bufferAmount) {
    }

    public record DayRs(LocalDate date, String baseBalance, String scenarioBalance, String delta, String baseInflow,
                        String scenarioInflow, String baseOutflow, String scenarioOutflow, String scenarioBalanceP10,
                        String scenarioBalanceP50, String scenarioBalanceP90) {
        public static DayRs of(ScenarioDay d) {
            return new DayRs(d.date(), m(d.baseBalance()), m(d.scenarioBalance()), m(d.delta()), m(d.baseInflow()),
                    m(d.scenarioInflow()), m(d.baseOutflow()), m(d.scenarioOutflow()), m(d.scenarioP10()),
                    m(d.scenarioP50()), m(d.scenarioP90()));
        }
    }

    public record SummaryRs(String minimumBalance, LocalDate minimumBalanceDate, String endBalance,
                            LocalDate firstGapDate, String balanceOnFirstGapDate, String maxShortfall,
                            LocalDate maxShortfallDate, int daysBelowZero, LocalDate firstBufferBreachDate) {
        public static SummaryRs of(Summary s) {
            return new SummaryRs(m(s.minimumBalance()), s.minimumBalanceDate(), m(s.endBalance()), s.firstGapDate(),
                    m(s.balanceOnFirstGapDate()), m(s.maxShortfall()), s.maxShortfallDate(), s.daysBelowZero(),
                    s.firstBufferBreachDate());
        }
    }

    public record EffectRs(Type type, String title, UUID planId, String amount, LocalDate date, Integer delayDays,
                           String percent, LocalDate fromDate, CostTarget target, String movedAmount,
                           String droppedAmount, LocalDate movedTo, String endBalanceDelta,
                           String minimumBalanceDelta, String note) {
        public static EffectRs of(Effect e) {
            var c = e.change();
            return new EffectRs(c.type(), c.title(), c.planId(), m(c.amount()), c.date(), c.delayDays(),
                    m(c.percent()), c.fromDate(), c.target(), m(e.movedAmount()), m(e.droppedAmount()), e.movedTo(),
                    m(e.endBalanceDelta()), m(e.minimumBalanceDelta()), e.note());
        }
    }

    /**
     * newGap: the scenario creates a cash gap the base forecast does not have; gapEarlier: both have a gap and the
     * scenario one starts earlier; additionalShortfall: how much deeper the deepest point gets.
     */
    public record ComparisonRs(SummaryRs base, SummaryRs scenario, boolean newGap, boolean gapEarlier,
                               String additionalShortfall, Integer gapShiftDays) {
    }

    private static BaseForecastRs baseOf(Preview p) {
        var b = p.baseForecast();
        return new BaseForecastRs(b.id(), b.asOfDate(), b.scope(), b.staleness(), b.createdAt(), m(b.startBalance()),
                m(b.buffer()));
    }

    public record ScenarioRs(BaseForecastRs baseForecast, ComparisonRs comparison, List<DayRs> days,
                             List<EffectRs> changes, List<String> notes) {
        public static ScenarioRs of(Preview p) {
            var c = p.comparison();
            return new ScenarioRs(baseOf(p),
                    new ComparisonRs(SummaryRs.of(c.base()), SummaryRs.of(c.scenario()), c.newGap(), c.gapEarlier(),
                            m(c.additionalShortfall()), c.gapShiftDays()),
                    p.days().stream().map(DayRs::of).toList(), p.changes().stream().map(EffectRs::of).toList(),
                    p.notes());
        }
    }

    static String m(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
