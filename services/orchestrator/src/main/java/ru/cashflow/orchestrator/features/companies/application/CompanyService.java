package ru.cashflow.orchestrator.features.companies.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;
import ru.cashflow.orchestrator.features.companies.model.Membership;
import ru.cashflow.orchestrator.features.companies.model.Role;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;

@Service
public class CompanyService {

    private final CompanyRepository repository;
    private final CompanyAccess access;
    private final Clock clock;

    public CompanyService(CompanyRepository repository, CompanyAccess access, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.clock = clock;
    }

    /** Company and its single OWNER membership are written in one transaction (handoff §2). */
    @Transactional
    public Company create(UUID userId, String name, LegalForm legalForm, String inn, String kpp, String timezone) {
        Instant now = clock.instant();
        var company = new Company(UUID.randomUUID(), name.strip(), legalForm, blankToNull(inn), blankToNull(kpp),
                validTimezone(timezone), userId, false, 0, 0, now, now);
        repository.insertCompany(company);
        repository.insertMembership(new Membership(company.id(), userId, Role.OWNER, 0, now));
        return company;
    }

    public List<Company> list(UUID userId) {
        return repository.listCompaniesForUser(userId);
    }

    public Company get(UUID userId, UUID companyId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return repository.findCompany(scope).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Company update(UUID userId, UUID companyId, long version, String name, LegalForm legalForm, String inn,
                          String kpp, String timezone) {
        CompanyScope scope = access.requireOwner(userId, companyId);
        if (!repository.updateCompany(scope, version, name.strip(), legalForm, blankToNull(inn), blankToNull(kpp),
                validTimezone(timezone), clock.instant())) {
            throw ApiException.conflict("VERSION_CONFLICT", "Компания изменена другим запросом; обновите данные");
        }
        return repository.findCompany(scope).orElseThrow(ApiException::notFound);
    }

    public List<Membership> members(UUID userId, UUID companyId) {
        return repository.listMembers(access.requireRead(userId, companyId));
    }

    @Transactional
    public Membership addMember(UUID userId, UUID companyId, UUID memberId, Role role) {
        CompanyScope scope = access.requireOwner(userId, companyId);
        if (role == Role.OWNER) {
            throw ApiException.unprocessable("SINGLE_OWNER", "У компании может быть только один владелец");
        }
        var membership = new Membership(scope.companyId(), memberId, role, 0, clock.instant());
        try {
            repository.insertMembership(membership);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("MEMBER_EXISTS", "Пользователь уже участник компании");
        }
        return membership;
    }

    @Transactional
    public void changeRole(UUID userId, UUID companyId, UUID memberId, Role role, long version) {
        CompanyScope scope = access.requireOwner(userId, companyId);
        if (role == Role.OWNER) {
            throw ApiException.unprocessable("SINGLE_OWNER", "Передача владения не поддерживается в v1");
        }
        if (!repository.updateMemberRole(scope, memberId, role, version)) {
            throw ApiException.conflict("VERSION_CONFLICT", "Участник не найден, изменён или является владельцем");
        }
    }

    @Transactional
    public void removeMember(UUID userId, UUID companyId, UUID memberId) {
        CompanyScope scope = access.requireOwner(userId, companyId);
        if (!repository.deleteMember(scope, memberId)) {
            throw ApiException.notFound();
        }
    }

    @Transactional
    public Account createAccount(UUID userId, UUID companyId, String name, String bankName, String accountNumber,
                                 String externalCompanyId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        Instant now = clock.instant();
        var account = new Account(UUID.randomUUID(), scope.companyId(), name.strip(), blankToNull(bankName),
                blankToNull(accountNumber), blankToNull(externalCompanyId), "RUB", false, 0, now, now);
        try {
            repository.insertAccount(account);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("EXTERNAL_ID_TAKEN", "Внешний ID уже связан с другим счётом компании");
        }
        return account;
    }

    public List<Account> accounts(UUID userId, UUID companyId) {
        return repository.listAccounts(access.requireRead(userId, companyId));
    }

    public Account account(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return repository.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Account updateAccount(UUID userId, UUID companyId, UUID accountId, long version, String name,
                                 String bankName, String accountNumber, String externalCompanyId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        try {
            if (!repository.updateAccount(scope, accountId, version, name.strip(), blankToNull(bankName),
                    blankToNull(accountNumber), blankToNull(externalCompanyId), clock.instant())) {
                repository.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
                throw ApiException.conflict("VERSION_CONFLICT", "Счёт изменён другим запросом; обновите данные");
            }
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("EXTERNAL_ID_TAKEN", "Внешний ID уже связан с другим счётом компании");
        }
        return repository.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
    }

    private static String validTimezone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "Europe/Moscow" : timezone.strip();
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException exception) {
            throw ApiException.unprocessable("INVALID_TIMEZONE", "Неизвестный часовой пояс");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
