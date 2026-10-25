package ru.cashflow.orchestrator.features.companies.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.command.RussianTaxIds;
import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.AccountView;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.model.CompanyView;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;
import ru.cashflow.orchestrator.features.companies.model.Membership;
import ru.cashflow.orchestrator.features.companies.model.Role;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.paging.model.PageResult;

@Service
public class CompanyService {

    private static final String OPERATION_ARCHIVE_COMPANY = "ARCHIVE_COMPANY";
    private static final String OPERATION_ARCHIVE_ACCOUNT = "ARCHIVE_ACCOUNT";

    private final CompanyRepository repository;
    private final CompanyAccess access;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public CompanyService(CompanyRepository repository, CompanyAccess access, IdempotencyRepository idempotency,
                          Clock clock) {
        this.repository = repository;
        this.access = access;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** Company and its single OWNER membership are written in one transaction (handoff §2). */
    @Transactional
    public CompanyView create(UUID userId, String name, LegalForm legalForm, String inn, String kpp, String timezone) {
        String normalizedInn = blankToNull(inn);
        String normalizedKpp = blankToNull(kpp);
        validateTaxIds(normalizedInn, legalForm, normalizedKpp);
        Instant now = clock.instant();
        var company = new Company(UUID.randomUUID(), name.strip(), legalForm, normalizedInn, normalizedKpp,
                validTimezone(timezone), userId, false, 0, 0, now, now);
        repository.insertCompany(company);
        repository.insertMembership(new Membership(company.id(), userId, Role.OWNER, 0, now));
        return new CompanyView(company, Role.OWNER);
    }

    public PageResult<CompanyView> list(UUID userId, PageQuery page) {
        return PageResult.of(repository.listCompaniesForUser(userId, page.offset(), page.size()),
                repository.countCompaniesForUser(userId));
    }

    public CompanyView get(UUID userId, UUID companyId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return new CompanyView(repository.findCompany(scope).orElseThrow(ApiException::notFound), scope.role());
    }

    @Transactional
    public CompanyView update(UUID userId, UUID companyId, long version, String name, LegalForm legalForm, String inn,
                              String kpp, String timezone) {
        CompanyScope scope = access.requireOwner(userId, companyId);
        if (repository.findCompany(scope).map(Company::archived).orElse(true)) {
            throw ApiException.forbidden();
        }
        String normalizedInn = blankToNull(inn);
        String normalizedKpp = blankToNull(kpp);
        validateTaxIds(normalizedInn, legalForm, normalizedKpp);
        if (!repository.updateCompany(scope, version, name.strip(), legalForm, normalizedInn, normalizedKpp,
                validTimezone(timezone), clock.instant())) {
            throw ApiException.conflict("VERSION_CONFLICT", "Компания изменена другим запросом; обновите данные");
        }
        return new CompanyView(repository.findCompany(scope).orElseThrow(ApiException::notFound), scope.role());
    }

    /**
     * Archive is idempotent by Idempotency-Key: a replay with the same version returns the archived company,
     * a reused key with another request is 409 and the company itself is read-only afterwards.
     */
    @Transactional
    public CompanyView archive(UUID userId, UUID companyId, long version, String idempotencyKey) {
        validateKey(idempotencyKey);
        CompanyScope scope = access.requireRead(userId, companyId);
        String requestHash = sha256(companyId + "|" + version);
        Optional<IdempotencyRepository.Record> replay = idempotency.find(scope, OPERATION_ARCHIVE_COMPANY,
                idempotencyKey);
        if (replay.isPresent()) {
            return replayCompany(scope, replay.get(), requestHash);
        }
        if (!scope.role().canManageMembers()) {
            throw ApiException.forbidden();
        }
        if (repository.findCompany(scope).map(Company::archived).orElse(true)) {
            throw ApiException.forbidden();
        }
        Instant now = clock.instant();
        if (!repository.archiveCompany(scope, version, now)) {
            throw ApiException.conflict("VERSION_CONFLICT", "Компания изменена другим запросом; обновите данные");
        }
        if (!idempotency.insert(scope, OPERATION_ARCHIVE_COMPANY, idempotencyKey, requestHash, companyId, now)) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос: он уже выполняется");
        }
        return new CompanyView(repository.findCompany(scope).orElseThrow(ApiException::notFound), scope.role());
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
    public AccountView createAccount(UUID userId, UUID companyId, String name, String bankName, String accountNumber,
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
        return new AccountView(account, null, null);
    }

    public PageResult<AccountView> accounts(UUID userId, UUID companyId, PageQuery page) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return PageResult.of(repository.listAccountViews(scope, page.offset(), page.size()),
                repository.countAccounts(scope));
    }

    public AccountView account(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return repository.findAccountView(scope, accountId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountView updateAccount(UUID userId, UUID companyId, UUID accountId, long version, String name,
                                     String bankName, String accountNumber, String externalCompanyId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        Account account = repository.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        try {
            if (!repository.updateAccount(scope, accountId, version, name.strip(), blankToNull(bankName),
                    blankToNull(accountNumber), blankToNull(externalCompanyId), clock.instant())) {
                throw ApiException.conflict("VERSION_CONFLICT", "Счёт изменён другим запросом; обновите данные");
            }
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("EXTERNAL_ID_TAKEN", "Внешний ID уже связан с другим счётом компании");
        }
        return repository.findAccountView(scope, accountId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountView archiveAccount(UUID userId, UUID companyId, UUID accountId, long version,
                                      String idempotencyKey) {
        validateKey(idempotencyKey);
        CompanyScope scope = access.requireRead(userId, companyId);
        String requestHash = sha256(companyId + "|" + accountId + "|" + version);
        Optional<IdempotencyRepository.Record> replay = idempotency.find(scope, OPERATION_ARCHIVE_ACCOUNT,
                idempotencyKey);
        if (replay.isPresent()) {
            return replayAccount(scope, replay.get(), requestHash, accountId);
        }
        if (!scope.role().canManageMembers()) {
            throw ApiException.forbidden();
        }
        Account account = repository.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        Instant now = clock.instant();
        if (!repository.archiveAccount(scope, accountId, version, now)) {
            throw ApiException.conflict("VERSION_CONFLICT", "Счёт изменён другим запросом; обновите данные");
        }
        if (!idempotency.insert(scope, OPERATION_ARCHIVE_ACCOUNT, idempotencyKey, requestHash, accountId, now)) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос: он уже выполняется");
        }
        return repository.findAccountView(scope, accountId).orElseThrow(ApiException::notFound);
    }

    private CompanyView replayCompany(CompanyScope scope, IdempotencyRepository.Record record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Ключ идемпотентности уже использован с другим запросом");
        }
        return new CompanyView(repository.findCompany(scope).orElseThrow(ApiException::notFound), scope.role());
    }

    private AccountView replayAccount(CompanyScope scope, IdempotencyRepository.Record record, String requestHash,
                                      UUID accountId) {
        if (!record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Ключ идемпотентности уже использован с другим запросом");
        }
        return repository.findAccountView(scope, accountId).orElseThrow(ApiException::notFound);
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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

    private static void validateTaxIds(String inn, LegalForm legalForm, String kpp) {
        if (!RussianTaxIds.isValidCompanyInn(inn, legalForm)) {
            throw ApiException.unprocessable("INVALID_INN",
                    "ИНН не прошёл проверку: для ООО нужен 10-значный ИНН, для ИП — 12-значный ИНН физлица, "
                            + "контрольная сумма должна совпадать");
        }
        if (!RussianTaxIds.isValidKpp(kpp)) {
            throw ApiException.unprocessable("INVALID_KPP",
                    "КПП должен содержать 9 цифр в формате 4+2+3 и корректный код налогового органа");
        }
    }
}
