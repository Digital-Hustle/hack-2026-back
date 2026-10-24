package ru.cashflow.orchestrator.api.controller;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.LedgerDtos;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.settings.application.SettingsService;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/forecast-settings")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return SettingsRs.of(service.get(user.userId(), companyId, accountId));
    }

    @PutMapping
    public SettingsRs update(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                             @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody SettingsRq rq) {
        var settings = new ForecastSettings(rq.windowWeeks(), rq.horizonDays(), rq.bufferAmount(), rq.scenarioCount(),
                rq.minCalibrationOrigins(), rq.calibrationStepDays(), rq.algorithm());
        return SettingsRs.of(service.update(user.userId(), companyId, accountId, CompanyController.version(ifMatch),
                settings));
    }

    public record SettingsRq(@NotNull Integer windowWeeks, @NotNull Integer horizonDays,
                             @NotNull @Digits(integer = 17, fraction = 2) BigDecimal bufferAmount,
                             @NotNull Integer scenarioCount, @NotNull Integer minCalibrationOrigins,
                             @NotNull Integer calibrationStepDays, @NotNull String algorithm) {
    }

    public record SettingsRs(int windowWeeks, int horizonDays, String bufferAmount, int scenarioCount,
                             int minCalibrationOrigins, int calibrationStepDays, String algorithm, long version) {
        static SettingsRs of(SettingsService.Versioned v) {
            var s = v.settings();
            return new SettingsRs(s.windowWeeks(), s.horizonDays(), LedgerDtos.money(s.bufferAmount()),
                    s.scenarioCount(), s.minCalibrationOrigins(), s.calibrationStepDays(), s.algorithm(), v.version());
        }
    }
}
