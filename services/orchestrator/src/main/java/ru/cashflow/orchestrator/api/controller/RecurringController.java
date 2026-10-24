package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/recurring-patterns")
public class RecurringController {

    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @GetMapping
    public ItemsRs<RecurringPatternRs> list(CurrentUser user, @PathVariable UUID companyId,
                                            @PathVariable UUID accountId) {
        return new ItemsRs<>(service.list(user.userId(), companyId, accountId).stream().map(RecurringPatternRs::of)
                .toList());
    }

    @PutMapping("/{patternId}/status")
    public RecurringPatternRs decide(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                     @PathVariable UUID patternId, @RequestHeader("If-Match") String ifMatch,
                                     @Valid @RequestBody DecisionRq rq) {
        return RecurringPatternRs.of(service.decide(user.userId(), companyId, accountId, patternId,
                CompanyController.version(ifMatch), rq.status()));
    }

    public record DecisionRq(@NotNull PatternStatus status) {
    }

    public record ScheduleRs(String frequency, Integer weekday, Integer dayOfMonth, String businessDayRule) {
    }

    /** RecurringPatternRs of the Forecast contract plus statusSource for the UI. */
    public record RecurringPatternRs(UUID id, UUID accountId, String direction, String categoryCode,
                                     String counterpartyKey, String status, String statusSource, ScheduleRs schedule,
                                     String amount, int observations, java.time.LocalDate firstSeen,
                                     java.time.LocalDate lastSeen, long inputDataVersion, String detectorVersion,
                                     long version) {
        static RecurringPatternRs of(RecurringPattern p) {
            return new RecurringPatternRs(p.id(), p.accountId(), p.direction().name(), p.categoryCode(),
                    p.counterpartyKey(), p.status().name(), p.statusSource().name(),
                    new ScheduleRs(p.schedule().frequency().name(), p.schedule().weekday(), p.schedule().dayOfMonth(),
                            p.schedule().businessDayRule().name()),
                    LedgerDtos.money(p.amount()), p.observations(), p.firstSeen(), p.lastSeen(), p.inputDataVersion(),
                    p.detectorVersion(), p.version());
        }
    }
}
