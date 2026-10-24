package ru.cashflow.forecast.api.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.api.validation.MoneyValue;

public record SnapshotPayment(@NotNull UUID paymentId, @NotNull LocalDate date,
        @NotNull @MoneyValue(sign = MoneyValue.Sign.POSITIVE) BigDecimal amount,
        @NotNull Direction direction, @NotBlank @Size(max = 64) String categoryCode,
        @Size(max = 150) String counterpartyKey) { }
