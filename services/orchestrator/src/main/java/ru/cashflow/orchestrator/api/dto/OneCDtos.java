package ru.cashflow.orchestrator.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.integrations.onec.application.OneCDemoService;
import ru.cashflow.orchestrator.features.integrations.onec.application.OneCDemoService.Item;
import ru.cashflow.orchestrator.features.integrations.onec.application.OneCDemoService.Report;

public final class OneCDtos {

    private OneCDtos() {
    }

    /** Both fields optional: an empty body loads the register for the window starting today. */
    public record SyncRq(LocalDate fromDate, Boolean dryRun) {
        public static SyncRq empty() {
            return new SyncRq(null, null);
        }
    }

    public record ItemRs(String externalId, String documentNumber, String title, String direction, String amount,
                         LocalDate dueDate, String action, String reason, UUID planId) {
        static ItemRs of(Item i) {
            return new ItemRs(i.externalId(), i.documentNumber(), i.title(), i.direction().name(),
                    LedgerDtos.money(i.amount()), i.dueDate(), i.action().name(), i.reason(), i.planId());
        }
    }

    public record SyncRs(String source, boolean demo, boolean dryRun, LocalDate from, int created, int updated,
                         int unchanged, int skipped, boolean dataChanged, List<ItemRs> items) {
        public static SyncRs of(Report r) {
            return new SyncRs(OneCDemoService.EXTERNAL_SYSTEM, true, r.dryRun(), r.from(), r.created(), r.updated(),
                    r.unchanged(), r.skipped(), r.dataChanged(), r.items().stream().map(ItemRs::of).toList());
        }
    }
}
