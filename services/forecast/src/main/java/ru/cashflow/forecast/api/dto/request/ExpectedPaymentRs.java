package ru.cashflow.forecast.api.dto.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.PlanRelation;
import ru.cashflow.forecast.features.shared.model.PlanStatus;
import ru.cashflow.forecast.features.shared.model.PaymentSource;
import ru.cashflow.forecast.api.validation.MoneyValue;

public record ExpectedPaymentRs(@NotNull UUID id, @NotNull UUID companyId, @NotNull UUID accountId,
        @NotBlank String title, @NotNull Direction direction,
        @NotNull @MoneyValue(sign = MoneyValue.Sign.POSITIVE) BigDecimal amount, @NotNull LocalDate dueDate,
        @NotBlank String categoryCode, String counterpartyKey, String note,
        @NotNull PlanRelation relation, UUID recurringPatternId, String baselineCounterpartyKey,
        @NotNull PaymentSource source, @Valid SourceDocument sourceDocument, @NotNull PlanStatus status,
        @NotNull @MoneyValue(sign = MoneyValue.Sign.NON_NEGATIVE) BigDecimal settledAmount,
        @NotNull @MoneyValue(sign = MoneyValue.Sign.NON_NEGATIVE) BigDecimal remainingAmount,
        @NotNull Boolean overdue, @NotNull Instant knownAt, @NotNull Instant createdAt,
        @NotNull Instant updatedAt, @NotNull @Min(1) Long version) { }
