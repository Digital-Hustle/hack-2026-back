package ru.cashflow.orchestrator.features.companies.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.model.Membership;
import ru.cashflow.orchestrator.features.companies.model.Role;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;

/**
 * The single place that turns a trusted user id plus a requested companyId into a CompanyScope.
 * A missing membership and a missing company both return 404, so tenants cannot probe each other.
 */
@Service
public class CompanyAccess {

    private final CompanyRepository repository;

    public CompanyAccess(CompanyRepository repository) {
        this.repository = repository;
    }

    public CompanyScope requireRead(UUID userId, UUID companyId) {
        Membership membership = repository.findMembership(companyId, userId).orElseThrow(ApiException::notFound);
        return new CompanyScope(companyId, userId, membership.role());
    }

    public CompanyScope requireWrite(UUID userId, UUID companyId) {
        CompanyScope scope = requireRead(userId, companyId);
        if (!scope.role().canWriteFinance()) {
            throw ApiException.forbidden();
        }
        if (repository.findCompany(scope).map(Company::archived).orElse(true)) {
            throw ApiException.forbidden();
        }
        return scope;
    }

    public CompanyScope requireOwner(UUID userId, UUID companyId) {
        CompanyScope scope = requireRead(userId, companyId);
        if (!scope.role().canManageMembers()) {
            throw ApiException.forbidden();
        }
        return scope;
    }

    /**
     * Trusted internal scope for background reconciliation. It still requires an existing, non-archived company,
     * but it deliberately does not pretend that a human made the request: the company owner is recorded as the actor.
     */
    public CompanyScope systemScope(UUID companyId) {
        Company company = repository.findCompanyById(companyId)
                .filter(candidate -> !candidate.archived())
                .orElseThrow(ApiException::notFound);
        return new CompanyScope(companyId, company.ownerUserId(), Role.OWNER);
    }
}
