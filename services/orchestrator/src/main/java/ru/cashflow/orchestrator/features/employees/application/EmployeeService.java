package ru.cashflow.orchestrator.features.employees.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.command.RussianTaxIds;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.employees.model.Employee;
import ru.cashflow.orchestrator.features.employees.model.EmployeeDraft;
import ru.cashflow.orchestrator.features.employees.model.EmployeeView;
import ru.cashflow.orchestrator.features.employees.model.SalaryDraft;
import ru.cashflow.orchestrator.features.employees.repository.EmployeeRepository;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.paging.model.PageResult;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.StatusSource;
import ru.cashflow.orchestrator.features.recurring.repository.RecurringRepository;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;

/** Employee payroll is an ordinary confirmed recurring OUT, never an additional parallel forecast stream. */
@Service
public class EmployeeService {
    private static final String CREATE = "CREATE_EMPLOYEE";
    private final EmployeeRepository employees;
    private final RecurringRepository patterns;
    private final RecurringService recurring;
    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final IdempotencyRepository audit;
    private final ObjectMapper json;
    private final Clock clock;

    public EmployeeService(EmployeeRepository employees, RecurringRepository patterns, RecurringService recurring,
                           CompanyAccess access, CompanyRepository companies, IdempotencyRepository audit,
                           ObjectMapper json, Clock clock) {
        this.employees = employees;
        this.patterns = patterns;
        this.recurring = recurring;
        this.access = access;
        this.companies = companies;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public EmployeeView create(UUID user, UUID company, EmployeeDraft draft, String key) {
        CompanyScope scope = write(user, company);
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
        EmployeeDraft normalized = normalize(draft);
        validateEmployeeInn(normalized.inn());
        String hash = Fingerprint.of(json.valueToTree(normalized));
        var replay = audit.find(scope, CREATE, key);
        if (replay.isPresent()) {
            if (!replay.get().requestHash().equals(hash)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Ключ уже использован с другим запросом");
            }
            return view(scope, find(scope, replay.get().resourceId()));
        }
        Instant now = clock.instant();
        Employee e = new Employee(UUID.randomUUID(), company, normalized.fullName(), normalized.position(),
                normalized.note(), normalized.inn(), false, null, 0, now, now);
        employees.insert(e);
        if (!audit.insert(scope, CREATE, key, hash, e.id(), now)) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос");
        }
        audit.audit(scope, "EMPLOYEE_CREATED", "EMPLOYEE", e.id(), now);
        return view(scope, e);
    }

    @Transactional(readOnly = true)
    public EmployeeView get(UUID user, UUID company, UUID id) {
        CompanyScope scope = access.requireRead(user, company);
        return view(scope, find(scope, id));
    }

    @Transactional(readOnly = true)
    public PageResult<EmployeeView> list(UUID user, UUID company, boolean archived, PageQuery page) {
        CompanyScope scope = access.requireRead(user, company);
        var items = employees.list(scope, archived, page);
        var linkedIds = items.stream().map(Employee::recurringPatternId)
                .filter(Objects::nonNull).distinct().toList();
        var linked = patterns.findByIds(scope, linkedIds).stream()
                .collect(java.util.stream.Collectors.toMap(RecurringPattern::id, p -> p));
        return PageResult.of(items.stream()
                        .map(e -> new EmployeeView(e, linked.get(e.recurringPatternId()))).toList(),
                employees.count(scope, archived));
    }

    @Transactional
    public EmployeeView update(UUID user, UUID company, UUID id, long version, EmployeeDraft draft) {
        CompanyScope scope = write(user, company);
        Employee current = editable(scope, id, version);
        EmployeeDraft normalized = normalize(draft);
        validateEmployeeInn(normalized.inn());
        Employee next = new Employee(id, company, normalized.fullName(), normalized.position(), normalized.note(),
                normalized.inn(), false, current.recurringPatternId(), version + 1, current.createdAt(), clock.instant());
        save(scope, next, "EMPLOYEE_UPDATED");
        return view(scope, next);
    }

    @Transactional
    public EmployeeView salary(UUID user, UUID company, UUID id, long version, SalaryDraft draft) {
        CompanyScope scope = write(user, company);
        Employee current = editable(scope, id, version);
        var account = companies.findAccount(scope, draft.accountId()).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        if (draft.schedule() == null || draft.schedule().frequency() == null
                || draft.schedule().businessDayRule() == null || !draft.schedule().valid()) {
            throw ApiException.unprocessable("INVALID_SCHEDULE", "Укажите корректное WEEKLY/MONTHLY расписание");
        }
        BigDecimal amount = money(draft.amount());
        String counterpartyInn = clean(draft.counterpartyInn());
        if (counterpartyInn != null && !RussianTaxIds.isValidIndividualInn(counterpartyInn)) {
            throw ApiException.unprocessable("INVALID_INN",
                    "ИНН получателя зарплаты должен быть 12-значным ИНН физлица с корректной контрольной суммой");
        }
        String cp = CounterpartyKeys.of(counterpartyInn, clean(draft.counterpartyName()));
        if (cp == null) {
            throw ApiException.unprocessable("COUNTERPARTY_REQUIRED", "Укажите ИНН или имя получателя как в выписке");
        }
        Instant now = clock.instant();
        RecurringPattern pattern;
        if (current.recurringPatternId() == null) {
            String stableKey = RecurringTypes.stableKey(account.id(), "SALARY", Direction.OUT, cp);
            if (patterns.lockByKey(scope, account.id(), stableKey).isPresent()) {
                throw ApiException.conflict("RECURRING_ALREADY_EXISTS", "Привяжите существующую регулярность");
            }
            long dataVersion = companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion() + 1;
            pattern = new RecurringPattern(UUID.randomUUID(), company, account.id(), stableKey, Direction.OUT, "SALARY",
                    cp, PatternStatus.CONFIRMED, StatusSource.USER, draft.schedule(), amount, 0, null, null,
                    RecurringTypes.MANUAL_VERSION, dataVersion, now, now, 0, true);
            patterns.insert(pattern);
        } else {
            RecurringPattern p = linked(scope, current);
            if (draft.recurringPatternVersion() == null) {
                throw new ApiException(428, "PATTERN_VERSION_REQUIRED", "Укажите recurringPatternVersion");
            }
            if (p.version() != draft.recurringPatternVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT", "Регулярность изменена; обновите данные");
            }
            if (!p.accountId().equals(account.id()) || !p.categoryCode().equals("SALARY")
                    || !Objects.equals(p.counterpartyKey(), cp)) {
                throw ApiException.unprocessable("PAYROLL_IDENTITY_CHANGED",
                        "Сначала отключите прежний платёж; счёт, категория и получатель связанной зарплаты неизменны");
            }
            pattern = revised(p, PatternStatus.CONFIRMED, draft.schedule(), amount, now);
            patterns.update(pattern, p.version());
        }
        patterns.insertRevision(pattern, recurring.payload(pattern), user);
        companies.incrementDataVersion(scope, now);
        Employee next = changed(current, pattern.id(), false, now);
        save(scope, next, "EMPLOYEE_SALARY_UPDATED");
        return new EmployeeView(next, pattern);
    }

    @Transactional
    public EmployeeView bind(UUID user, UUID company, UUID id, long version, UUID account, UUID patternId,
                             long patternVersion) {
        CompanyScope scope = write(user, company);
        Employee current = editable(scope, id, version);
        var a = companies.findAccount(scope, account).orElseThrow(ApiException::notFound);
        if (a.archived()) {
            throw ApiException.forbidden();
        }
        RecurringPattern p = patterns.lock(scope, account, patternId).orElseThrow(ApiException::notFound);
        if (p.version() != patternVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "Регулярность изменена; обновите данные");
        }
        if (p.direction() != Direction.OUT) {
            throw ApiException.unprocessable("PAYROLL_DIRECTION", "Сотруднику можно привязать только списание OUT");
        }
        if (p.counterpartyKey() == null) {
            throw ApiException.unprocessable("COUNTERPARTY_REQUIRED", "У регулярного платежа должен быть получатель");
        }
        if (current.recurringPatternId() != null && !current.recurringPatternId().equals(patternId)) {
            throw ApiException.conflict("PAYROLL_ALREADY_LINKED", "Сначала отключите прежний платёж");
        }
        if (employees.byPattern(scope, patternId).filter(e -> !e.id().equals(id)).isPresent()) {
            throw ApiException.conflict("PAYROLL_ALREADY_LINKED", "Платёж уже привязан к другому сотруднику");
        }
        Instant now = clock.instant();
        RecurringPattern confirmed = revised(p, PatternStatus.CONFIRMED, p.schedule(), p.amount(), now);
        patterns.update(confirmed, p.version());
        patterns.insertRevision(confirmed, recurring.payload(confirmed), user);
        companies.incrementDataVersion(scope, now);
        Employee next = changed(current, patternId, false, now);
        save(scope, next, "EMPLOYEE_PAYMENT_LINKED");
        return new EmployeeView(next, confirmed);
    }

    /** Exclusion is revisioned. The recurring row is retained for old snapshots, plans and audit. */
    @Transactional
    public EmployeeView stop(UUID user, UUID company, UUID id, long version, boolean archive) {
        CompanyScope scope = write(user, company);
        Employee current = editable(scope, id, version);
        Instant now = clock.instant();
        RecurringPattern excluded = null;
        if (current.recurringPatternId() != null) {
            RecurringPattern p = linked(scope, current);
            excluded = revised(p, PatternStatus.EXCLUDED, p.schedule(), p.amount(), now);
            patterns.update(excluded, p.version());
            patterns.insertRevision(excluded, recurring.payload(excluded), user);
            companies.incrementDataVersion(scope, now);
        }
        Employee next = changed(current, archive ? current.recurringPatternId() : null, archive, now);
        save(scope, next, archive ? "EMPLOYEE_ARCHIVED" : "EMPLOYEE_PAYMENT_UNLINKED");
        return new EmployeeView(next, archive ? excluded : null);
    }

    private CompanyScope write(UUID user, UUID company) {
        CompanyScope scope = access.requireWrite(user, company);
        if (companies.lockCompany(scope).orElseThrow(ApiException::notFound).archived()) {
            throw ApiException.forbidden();
        }
        return scope;
    }

    private Employee editable(CompanyScope scope, UUID id, long version) {
        Employee e = find(scope, id);
        if (e.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Сотрудник изменён; обновите данные");
        }
        if (e.archived()) {
            throw ApiException.unprocessable("EMPLOYEE_ARCHIVED", "Сотрудник архивирован");
        }
        return e;
    }

    private Employee find(CompanyScope scope, UUID id) {
        return employees.find(scope, id).orElseThrow(ApiException::notFound);
    }

    private RecurringPattern linked(CompanyScope scope, Employee e) {
        return patterns.findById(scope, e.recurringPatternId()).orElseThrow(ApiException::notFound);
    }

    private EmployeeView view(CompanyScope scope, Employee e) {
        return new EmployeeView(e, e.recurringPatternId() == null ? null : linked(scope, e));
    }

    private void save(CompanyScope scope, Employee e, String action) {
        employees.update(e);
        audit.audit(scope, action, "EMPLOYEE", e.id(), e.updatedAt());
    }

    private static Employee changed(Employee e, UUID pattern, boolean archived, Instant now) {
        return new Employee(e.id(), e.companyId(), e.fullName(), e.position(), e.note(), e.inn(), archived, pattern,
                e.version() + 1, e.createdAt(), now);
    }

    private static RecurringPattern revised(RecurringPattern p, PatternStatus status,
                                           RecurringTypes.Schedule schedule, BigDecimal amount, Instant now) {
        return new RecurringPattern(p.id(), p.companyId(), p.accountId(), p.stableKey(), p.direction(), p.categoryCode(),
                p.counterpartyKey(), status, StatusSource.USER, schedule, amount, p.observations(), p.firstSeen(),
                p.lastSeen(), p.detectorVersion(), p.inputDataVersion(), p.knownAt(), now, p.version() + 1, true);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.precision() - (long) value.scale() > 17
                || value.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Сумма > 0, до 17 цифр и двух знаков после запятой");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static EmployeeDraft normalize(EmployeeDraft draft) {
        return new EmployeeDraft(draft.fullName().strip(), clean(draft.position()), clean(draft.note()),
                clean(draft.inn()));
    }

    private static void validateEmployeeInn(String inn) {
        if (inn != null && !RussianTaxIds.isValidIndividualInn(inn)) {
            throw ApiException.unprocessable("INVALID_INN",
                    "ИНН сотрудника должен быть 12-значным ИНН физлица с корректной контрольной суммой");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
