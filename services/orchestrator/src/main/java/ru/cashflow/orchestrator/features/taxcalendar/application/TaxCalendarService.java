package ru.cashflow.orchestrator.features.taxcalendar.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.taxcalendar.model.TaxDeadline;
import ru.cashflow.orchestrator.features.taxcalendar.repository.TaxCalendarRepository;

@Service
public class TaxCalendarService {

    private final CompanyAccess access;
    private final TaxCalendarRepository calendar;

    public TaxCalendarService(CompanyAccess access, TaxCalendarRepository calendar) {
        this.access = access;
        this.calendar = calendar;
    }

    public List<TaxDeadline> list(UUID userId, UUID companyId, int year) {
        access.requireRead(userId, companyId);
        return calendar.list(year);
    }
}
