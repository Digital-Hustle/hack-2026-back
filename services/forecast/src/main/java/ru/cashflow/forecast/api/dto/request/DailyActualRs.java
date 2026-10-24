package ru.cashflow.forecast.api.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.api.validation.MoneyValue;

public record DailyActualRs(@NotNull LocalDate date,
        @MoneyValue(sign = MoneyValue.Sign.NON_NEGATIVE) BigDecimal inflow,
        @MoneyValue(sign = MoneyValue.Sign.NON_NEGATIVE) BigDecimal outflow,
        @MoneyValue BigDecimal balanceEod, @NotNull DailyStatus status,
        @NotNull @Min(0) Integer paymentCount, @NotNull Boolean balanceMismatch) { }
