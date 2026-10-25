package ru.cashflow.orchestrator.features.ledger.command;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * When an uploaded statement or export becomes "known" for historical replay (backtest, calibration of the cash-gap
 * probability). A bank fact of day D existed at the end of D, so it is stamped with that moment instead of the upload
 * moment; otherwise replay at any past date sees an empty history and every origin is rejected. A replay at D uses
 * the exclusive end of D as its cutoff, hence one second earlier. Never later than {@code now}.
 */
public final class ArchiveKnowledge {

    private ArchiveKnowledge() {
    }

    public static Instant knownAtEndOfDay(LocalDate day, String timezone, Instant now) {
        Instant end = day.plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant().minusSeconds(1);
        return end.isAfter(now) ? now : end;
    }
}
