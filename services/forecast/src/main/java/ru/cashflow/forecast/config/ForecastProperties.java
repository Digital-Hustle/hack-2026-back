package ru.cashflow.forecast.config;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("forecast")
public record ForecastProperties(
        @DefaultValue("16777216") @Min(1024) @Max(67108864) int maxRequestBytes,
        @DefaultValue("2") @Min(1) @Max(16) int maxConcurrentRequests,
        @DefaultValue("250000") @Min(1) @Max(1000000) int maxTotalPayments,
        @DefaultValue("150000") @Min(30) @Max(1000000) int maxTotalDays,
        @DefaultValue("2048") @Min(1) @Max(10000) int maxSnapshots,
        @DefaultValue("3000000") @Min(3000) @Max(30000000) long maxScenarioCells,
        @DefaultValue({"127.0.0.1/32", "::1/128"}) @NotEmpty List<String> allowedCallerCidrs) {
    public ForecastProperties { allowedCallerCidrs = List.copyOf(allowedCallerCidrs); }
}
