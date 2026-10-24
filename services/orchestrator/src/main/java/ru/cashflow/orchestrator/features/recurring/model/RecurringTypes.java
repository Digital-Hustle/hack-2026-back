package ru.cashflow.orchestrator.features.recurring.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Shapes follow RecurringPatternRs/Schedule of the Forecast contract (handoff §4). */
public final class RecurringTypes {

    private RecurringTypes() {
    }

    public enum PatternStatus { DETECTED, CONFIRMED, EXCLUDED }

    public enum StatusSource { DETECTOR, USER }

    public enum Frequency { WEEKLY, MONTHLY }

    public enum BusinessDayRule { NONE, FOLLOWING }

    public record Schedule(Frequency frequency, Integer weekday, Integer dayOfMonth, BusinessDayRule businessDayRule) {

        public boolean valid() {
            return frequency == Frequency.WEEKLY
                    ? weekday != null && weekday >= 1 && weekday <= 7 && dayOfMonth == null
                    : weekday == null && dayOfMonth != null && dayOfMonth >= 1 && dayOfMonth <= 31;
        }
    }

    /** Candidate as returned by Forecast recurring-detection; always DETECTED, never a user decision. */
    public record DetectedCandidate(Direction direction, String categoryCode, String counterpartyKey, Schedule schedule,
                                    BigDecimal amount, int observations, LocalDate firstSeen, LocalDate lastSeen) {
    }

    public record RecurringPattern(UUID id, UUID companyId, UUID accountId, String stableKey, Direction direction,
                                   String categoryCode, String counterpartyKey, PatternStatus status,
                                   StatusSource statusSource, Schedule schedule, BigDecimal amount, int observations,
                                   LocalDate firstSeen, LocalDate lastSeen, String detectorVersion,
                                   long inputDataVersion, Instant knownAt, Instant updatedAt, long version) {
    }

    /**
     * Stable identity of a pattern: (accountId, categoryCode, direction, counterpartyKey), not a fresh UUID per
     * detection run (handoff §2). A null counterparty key is kept explicit so it never merges with a real one.
     */
    public static String stableKey(UUID accountId, String categoryCode, Direction direction, String counterpartyKey) {
        return accountId + "|" + categoryCode + "|" + direction.name() + "|"
                + (counterpartyKey == null ? "<none>" : "cp:" + counterpartyKey);
    }
}
