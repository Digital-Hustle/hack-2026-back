package ru.cashflow.orchestrator.features.integrations.bitrix.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.integrations.bitrix.application.BitrixSyncPlanner.Decision;
import ru.cashflow.orchestrator.features.integrations.bitrix.gateway.BitrixClient;
import ru.cashflow.orchestrator.features.integrations.bitrix.gateway.BitrixDemoData;
import ru.cashflow.orchestrator.features.integrations.bitrix.gateway.BitrixWebhook;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Action;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Mode;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Portal;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Status;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncOptions;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncReport;
import ru.cashflow.orchestrator.features.plans.application.PlanService;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;

/**
 * Pulls future incoming payments from Bitrix24 CRM into expected payments, so {@code WITH_KNOWN_PLANS} forecasts
 * see them. The CRM is read first, outside any transaction; then every decision is applied in one transaction under
 * the company lock with one dataVersion increment, a revision per changed plan and optimistic version checks. A
 * repeated sync of unchanged CRM data changes nothing. The webhook URL is used only for the call: it is not stored,
 * logged or returned (only the portal host is).
 */
@Service
public class BitrixSyncService {

    private static final Pattern CATEGORY = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final PlanRepository plans;
    private final PlanService planService;
    private final BitrixClient client;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String configuredWebhook;
    private final List<String> allowedHostSuffixes;

    public BitrixSyncService(CompanyAccess access, CompanyRepository companies, PlanRepository plans,
                             PlanService planService, BitrixClient client, TransactionTemplate tx, Clock clock,
                             @Value("${orchestrator.bitrix.webhook-url:}") String configuredWebhook,
                             @Value("${orchestrator.bitrix.allowed-host-suffixes:.bitrix24.ru,.bitrix24.com,"
                                     + ".bitrix24.by,.bitrix24.kz,.bitrix24.ua,.bitrix24.de,.bitrix24.eu}")
                             List<String> allowedHostSuffixes) {
        this.access = access;
        this.companies = companies;
        this.plans = plans;
        this.planService = planService;
        this.client = client;
        this.tx = tx;
        this.clock = clock;
        this.configuredWebhook = configuredWebhook == null ? "" : configuredWebhook.strip();
        this.allowedHostSuffixes = List.copyOf(allowedHostSuffixes);
    }

    public SyncReport sync(UUID userId, UUID companyId, UUID accountId, SyncOptions options) {
        validate(options);
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        LocalDate from = options.fromDate() == null ? today(scope) : options.fromDate();
        LocalDate to = from.plusDays(options.horizonDays());
        Portal portal;
        List<BitrixDocument> documents;
        if (options.mode() == Mode.DEMO) {
            portal = BitrixDemoData.PORTAL;
            documents = BitrixDemoData.documents(from);
        } else {
            String url = options.webhookUrl() == null || options.webhookUrl().isBlank()
                    ? configuredWebhook : options.webhookUrl();
            if (url.isBlank()) {
                throw ApiException.unprocessable("BITRIX_NOT_CONFIGURED",
                        "Укажите входящий вебхук Битрикс24 или включите демо-режим");
            }
            portal = BitrixWebhook.parse(url, allowedHostSuffixes);
            documents = client.fetch(portal, from, to, options.includeDeals(), options.includeInvoices());
        }
        if (options.dryRun()) {
            var decisions = BitrixSyncPlanner.plan(scope.companyId(), accountId, portal.host(), documents,
                    synced(scope, portal.host()), options, from, to, clock.instant());
            return report(options, portal, from, to, documents.size(), decisions, false);
        }
        Portal target = portal;
        return tx.execute(status -> apply(userId, scope, accountId, options, target, documents, from, to));
    }

    public Status status(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        List<ExpectedPayment> all = plans.listExternal(scope, BitrixTypes.EXTERNAL_SYSTEM, "").stream()
                .filter(p -> p.accountId().equals(accountId)).toList();
        int active = 0;
        BigDecimal amount = new BigDecimal("0.00");
        Instant last = null;
        for (ExpectedPayment plan : all) {
            if (plan.status() == PlanStatus.OPEN || plan.status() == PlanStatus.PARTIALLY_SETTLED) {
                active++;
                amount = amount.add(plan.remainingAmount());
            }
            if (last == null || plan.updatedAt().isAfter(last)) {
                last = plan.updatedAt();
            }
        }
        return new Status(!configuredWebhook.isEmpty(), configuredPortal(), allowedHostSuffixes, active, amount, last);
    }

    private SyncReport apply(UUID userId, CompanyScope scope, UUID accountId, SyncOptions options, Portal portal,
                             List<BitrixDocument> documents, LocalDate from, LocalDate to) {
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        var decisions = BitrixSyncPlanner.plan(scope.companyId(), accountId, portal.host(), documents,
                synced(scope, portal.host()), options, from, to, now);
        boolean changed = false;
        for (Decision decision : decisions) {
            if (!decision.changes()) {
                continue;
            }
            ExpectedPayment next = decision.next();
            if (decision.existing() == null) {
                try {
                    plans.insert(next);
                } catch (DuplicateKeyException exception) {
                    throw ApiException.conflict("CONCURRENT_SYNC", "Синхронизация уже выполняется, повторите позже");
                }
            } else {
                ExpectedPayment locked = plans.lock(scope, accountId, next.id()).orElseThrow(ApiException::notFound);
                if (locked.version() != decision.existing().version() || !plans.update(next, locked.version())) {
                    throw ApiException.conflict("VERSION_CONFLICT", "План изменён другим запросом; повторите синхронизацию");
                }
            }
            plans.insertRevision(next, planService.payload(next), userId);
            changed = true;
        }
        if (changed) {
            companies.incrementDataVersion(scope, now);
        }
        return report(options, portal, from, to, documents.size(), decisions, changed);
    }

    private List<ExpectedPayment> synced(CompanyScope scope, String host) {
        return plans.listExternal(scope, BitrixTypes.EXTERNAL_SYSTEM, host + "/");
    }

    private static SyncReport report(SyncOptions options, Portal portal, LocalDate from, LocalDate to, int fetched,
                                     List<Decision> decisions, boolean changed) {
        int[] counts = new int[Action.values().length];
        decisions.forEach(d -> counts[d.item().action().ordinal()]++);
        return new SyncReport(options.mode(), portal.host(), options.dryRun(), from, to, fetched,
                counts[Action.CREATED.ordinal()], counts[Action.UPDATED.ordinal()], counts[Action.REOPENED.ordinal()],
                counts[Action.CANCELLED.ordinal()], counts[Action.UNCHANGED.ordinal()],
                counts[Action.SKIPPED.ordinal()], changed, decisions.stream().map(Decision::item).toList());
    }

    private LocalDate today(CompanyScope scope) {
        String zone = companies.findCompany(scope).orElseThrow(ApiException::notFound).timezone();
        return LocalDate.now(clock.withZone(ZoneId.of(zone)));
    }

    private String configuredPortal() {
        if (configuredWebhook.isEmpty()) {
            return null;
        }
        try {
            return BitrixWebhook.parse(configuredWebhook, allowedHostSuffixes).host();
        } catch (ApiException exception) {
            return null;
        }
    }

    private static void validate(SyncOptions options) {
        if (options.horizonDays() < 1 || options.horizonDays() > BitrixTypes.MAX_HORIZON_DAYS) {
            throw ApiException.unprocessable("INVALID_HORIZON", "horizonDays должен быть от 1 до "
                    + BitrixTypes.MAX_HORIZON_DAYS);
        }
        if (!options.includeDeals() && !options.includeInvoices()) {
            throw ApiException.unprocessable("NOTHING_TO_SYNC", "Включите сделки и/или счета");
        }
        if (options.categoryCode() == null || !CATEGORY.matcher(options.categoryCode()).matches()) {
            throw ApiException.unprocessable("INVALID_CATEGORY", "categoryCode: ^[A-Z][A-Z0-9_]{1,63}$");
        }
    }
}
