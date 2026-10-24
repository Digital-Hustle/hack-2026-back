package ru.cashflow.orchestrator.features.ledger.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.ledger.command.DailyHistoryCalculator;
import ru.cashflow.orchestrator.features.ledger.model.CategoryCode;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.ManualPaymentDraft;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentView;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;

/**
 * Owner-entered facts. A manual payment is a normal immutable payment with source=MANUAL, no external identity and
 * no balance_eod. It is allowed only on a closed day that is not already confirmed complete by a statement, because
 * otherwise a cash-like fact would silently rewrite the meaning of a complete bank day.
 */
@Service
public class ManualPaymentService {

    public static final String OPERATION = "CREATE_MANUAL_PAYMENT";
    private static final Logger LOG = LoggerFactory.getLogger(ManualPaymentService.class);
    private static final Pattern INN = Pattern.compile("^([0-9]{10}|[0-9]{12})$");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000000000000");

    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public ManualPaymentService(LedgerRepository ledger, LedgerService ledgerService, CompanyRepository companies,
                                CompanyAccess access, IdempotencyRepository idempotency, Clock clock) {
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.companies = companies;
        this.access = access;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public PaymentView create(UUID userId, UUID companyId, ManualPaymentDraft draft, String idempotencyKey) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        validateKey(idempotencyKey);
        Account account = companies.findAccount(scope, draft.accountId()).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        Company company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        Validated validated = validate(draft, company);
        String requestHash = requestHash(draft, validated);

        Optional<IdempotencyRepository.Record> replay = idempotency.find(scope, OPERATION, idempotencyKey);
        if (replay.isPresent()) {
            return replay(scope, account.id(), replay.get(), requestHash);
        }

        // Serialise financial writes and projection rebuilds for this company.
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        replay = idempotency.find(scope, OPERATION, idempotencyKey);
        if (replay.isPresent()) {
            return replay(scope, account.id(), replay.get(), requestHash);
        }
        if (isComplete(scope, account.id(), validated.date)) {
            throw ApiException.unprocessable("DAY_ALREADY_COMPLETE",
                    "День уже подтверждён полным по выписке; ручной платёж не может изменить его остаток");
        }

        Instant now = clock.instant();
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment(paymentId, scope.companyId(), account.id(), null, null, null, validated.date,
                validated.amount, validated.direction, validated.counterpartyKey, validated.inn, validated.name,
                validated.purpose, validated.categoryCode, validated.categorySource, null, null, 0, now, now,
                PaymentSource.MANUAL, userId, validated.note);
        ledger.insertPayment(payment);
        long dataVersion = companies.incrementDataVersion(scope, now);
        ledgerService.rebuildProjection(scope, account.id(), dataVersion);
        idempotency.audit(scope, "MANUAL_PAYMENT_CREATED", "PAYMENT", paymentId, now);
        if (!idempotency.insert(scope, OPERATION, idempotencyKey, requestHash, paymentId, now)) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос: он уже выполняется");
        }
        LOG.info("manual_payment_created paymentId={}", paymentId);
        return ledger.findPaymentView(scope, account.id(), paymentId).orElseThrow(ApiException::notFound);
    }

    private PaymentView replay(CompanyScope scope, UUID accountId, IdempotencyRepository.Record record,
                               String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Ключ идемпотентности уже использован с другим запросом");
        }
        return ledger.findPaymentView(scope, accountId, record.resourceId()).orElseThrow(ApiException::notFound);
    }

    private boolean isComplete(CompanyScope scope, UUID accountId, LocalDate date) {
        List<CompletenessRange> ranges = ledger.completenessKnownBefore(scope, accountId, clock.instant().plusNanos(1));
        return DailyHistoryCalculator.isFullyConfirmed(date, date, ranges);
    }

    private Validated validate(ManualPaymentDraft draft, Company company) {
        if (draft.amount() == null || draft.amount().signum() <= 0) {
            throw invalid("amount", "Сумма должна быть положительной");
        }
        BigDecimal amount;
        try {
            amount = draft.amount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("amount", "Нужно не более двух знаков после запятой");
        }
        if (amount.compareTo(MAX_AMOUNT) >= 0) {
            throw invalid("amount", "Сумма не помещается в NUMERIC(19,2)");
        }
        if (draft.operationDate() == null) {
            throw invalid("operationDate", "Дата операции обязательна");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
        if (!draft.operationDate().isBefore(today)) {
            throw ApiException.unprocessable("OPEN_DAY", "Ручной платёж можно добавить только за закрытый день");
        }
        if (draft.direction() == null) {
            throw invalid("direction", "Направление операции обязательно");
        }
        String inn = clean(draft.counterpartyInn());
        if (inn != null && !INN.matcher(inn).matches()) {
            throw invalid("counterpartyInn", "ИНН должен содержать 10 или 12 цифр");
        }
        String name = limit(clean(draft.counterpartyName()), 500, "counterpartyName");
        String purpose = limit(clean(draft.purpose()), 2000, "purpose");
        String note = limit(clean(draft.note()), 500, "note");
        String category = clean(draft.categoryCode());
        CategorySource categorySource;
        if (category == null) {
            category = CategoryCode.OTHER.name();
            categorySource = CategorySource.OTHER;
        } else {
            try {
                CategoryCode.valueOf(category);
            } catch (IllegalArgumentException exception) {
                throw invalid("categoryCode", "Неизвестная категория");
            }
            categorySource = CategorySource.USER;
        }
        return new Validated(draft.operationDate(), amount, draft.direction(), CounterpartyKeys.of(inn, name), inn,
                name, purpose, category, categorySource, note);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String limit(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw invalid(field, "Поле длиннее допустимого");
        }
        return value;
    }

    private static String requestHash(ManualPaymentDraft draft, Validated validated) {
        return Fingerprint.sha256(String.join("|", String.valueOf(draft.accountId()),
                validated.date.toString(), validated.amount.toPlainString(), validated.direction.name(),
                nullSafe(validated.inn), nullSafe(validated.name), nullSafe(validated.purpose),
                validated.categoryCode, nullSafe(validated.note)));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY",
                    "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.unprocessable("VALIDATION_FAILED", field + ": " + message);
    }

    private record Validated(LocalDate date, BigDecimal amount, Direction direction, String counterpartyKey,
                             String inn, String name, String purpose, String categoryCode,
                             CategorySource categorySource, String note) {
    }
}
