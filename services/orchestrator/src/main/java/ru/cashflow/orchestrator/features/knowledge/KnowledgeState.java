package ru.cashflow.orchestrator.features.knowledge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.StartBalance;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;

/**
 * Everything the system knew about one account strictly before {@code knownBefore}, with business dates up to
 * {@code asOf}. Payments carry the category valid at that moment, not today's category. Plans carry the state and
 * settled amount from allocations active at the cutoff. Built only by KnowledgeReader.
 */
public record KnowledgeState(UUID companyId, UUID accountId, LocalDate asOf, Instant knownBefore,
                             List<Payment> payments, List<DailyObservation> days, List<CompletenessRange> completeness,
                             List<BalanceAnchor> anchors, List<ExpectedPayment> plans,
                             List<RecurringPattern> recurring, ForecastSettings settings,
                             Optional<StartBalance> startBalance, List<UUID> revisionsUsed) {
}
