package ru.cashflow.orchestrator.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Mode;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Status;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncItem;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncOptions;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncReport;

public final class BitrixDtos {

    private BitrixDtos() {
    }

    /** All fields optional: an empty body is a LIVE sync with the server-configured webhook for 30 days. */
    public record SyncRq(Mode mode, @Size(max = 300) String webhookUrl,
                         @Min(1) @Max(BitrixTypes.MAX_HORIZON_DAYS) Integer horizonDays, Boolean includeDeals,
                         Boolean includeInvoices, Boolean weightByProbability, @Size(max = 64) String categoryCode,
                         Boolean dryRun) {

        public SyncOptions toOptions() {
            return new SyncOptions(mode == null ? Mode.LIVE : mode, webhookUrl,
                    horizonDays == null ? BitrixTypes.DEFAULT_HORIZON_DAYS : horizonDays, !Boolean.FALSE.equals(includeDeals),
                    !Boolean.FALSE.equals(includeInvoices), Boolean.TRUE.equals(weightByProbability),
                    categoryCode == null || categoryCode.isBlank() ? BitrixTypes.DEFAULT_CATEGORY : categoryCode.strip(),
                    Boolean.TRUE.equals(dryRun));
        }

        public static SyncRq empty() {
            return new SyncRq(null, null, null, null, null, null, null, null);
        }
    }

    public record SyncItemRs(String kind, String externalId, String title, String documentAmount, String planAmount,
                             LocalDate dueDate, String stage, Integer probability, String action, String reason,
                             UUID planId) {

        static SyncItemRs of(SyncItem i) {
            return new SyncItemRs(i.kind().name(), i.externalId(), i.title(), LedgerDtos.money(i.documentAmount()),
                    LedgerDtos.money(i.planAmount()), i.dueDate(), i.stage() == null ? null : i.stage().name(),
                    i.probability(), i.action().name(), i.reason(), i.planId());
        }
    }

    public record SyncRs(String mode, String portal, boolean dryRun, LocalDate from, LocalDate to, int fetched,
                         int created, int updated, int reopened, int cancelled, int unchanged, int skipped,
                         boolean dataChanged, List<SyncItemRs> items) {

        public static SyncRs of(SyncReport r) {
            return new SyncRs(r.mode().name(), r.portal(), r.dryRun(), r.from(), r.to(), r.fetched(), r.created(),
                    r.updated(), r.reopened(), r.cancelled(), r.unchanged(), r.skipped(), r.dataChanged(),
                    r.items().stream().map(SyncItemRs::of).toList());
        }
    }

    public record StatusRs(boolean webhookConfigured, String configuredPortal, List<String> allowedHostSuffixes,
                           boolean demoAvailable, int activePlans, String activeAmount, Instant lastSyncedAt) {

        public static StatusRs of(Status s) {
            return new StatusRs(s.webhookConfigured(), s.configuredPortal(), s.allowedHostSuffixes(), true,
                    s.activePlans(), LedgerDtos.money(s.activeAmount()), s.lastSyncedAt());
        }
    }
}
