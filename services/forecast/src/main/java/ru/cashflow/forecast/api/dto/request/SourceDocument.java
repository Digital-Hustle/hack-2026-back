package ru.cashflow.forecast.api.dto.request;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;

public record SourceDocument(@NotBlank String externalSystem, @NotBlank String externalId,
        String documentNumber, LocalDate issuedDate) { }
