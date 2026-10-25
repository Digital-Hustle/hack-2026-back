package ru.cashflow.orchestrator.features.integrations.bitrix.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bitrix24 CRM documents that promise future money (deals and smart invoices) and the result of syncing them into
 * expected payments. Only incoming money is taken from Bitrix24: CRM rarely holds payables.
 */
public final class BitrixTypes {

    /** externalSystem of expected payments created by the sync. */
    public static final String EXTERNAL_SYSTEM = "BITRIX24";
    public static final int MAX_HORIZON_DAYS = 90;
    public static final int DEFAULT_HORIZON_DAYS = 30;
    public static final String DEFAULT_CATEGORY = "SALES";

    private BitrixTypes() {
    }

    public enum Mode { LIVE, DEMO }

    public enum DocumentKind {
        DEAL("deal"), INVOICE("invoice");

        private final String path;

        DocumentKind(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    /** Stage group: in progress, won (deal won / invoice paid) or lost (deal failed / invoice declined). */
    public enum Stage { OPEN, WON, LOST }

    public enum Action { CREATED, UPDATED, REOPENED, CANCELLED, UNCHANGED, SKIPPED }

    /**
     * One CRM document as read from Bitrix24 (or the demo fixture). Amount is the full document amount; probability
     * is the stage probability of a deal in percent, null for invoices or when Bitrix24 does not report it.
     */
    public record BitrixDocument(DocumentKind kind, String id, String title, BigDecimal amount, String currency,
                                 LocalDate dueDate, LocalDate issuedDate, Stage stage, String stageCode,
                                 Integer probability, String companyId, String companyName, String documentNumber) {
    }

    /** Parsed and validated incoming webhook: base URL keeps the secret token and must never be logged. */
    public record Portal(String host, String baseUrl) {

        @Override
        public String toString() {
            return "Portal[" + host + "]";
        }
    }

    /** {@code fromDate} is the first day of the window; null means today in the company time zone. */
    public record SyncOptions(Mode mode, String webhookUrl, int horizonDays, boolean includeDeals,
                              boolean includeInvoices, boolean weightByProbability, String categoryCode,
                              boolean dryRun, LocalDate fromDate) {

        public SyncOptions(Mode mode, String webhookUrl, int horizonDays, boolean includeDeals,
                           boolean includeInvoices, boolean weightByProbability, String categoryCode,
                           boolean dryRun) {
            this(mode, webhookUrl, horizonDays, includeDeals, includeInvoices, weightByProbability, categoryCode,
                    dryRun, null);
        }
    }

    public record SyncItem(DocumentKind kind, String externalId, String title, BigDecimal documentAmount,
                           BigDecimal planAmount, LocalDate dueDate, Stage stage, Integer probability,
                           Action action, String reason, UUID planId) {
    }

    public record SyncReport(Mode mode, String portal, boolean dryRun, LocalDate from, LocalDate to, int fetched,
                             int created, int updated, int reopened, int cancelled, int unchanged, int skipped,
                             boolean dataChanged, List<SyncItem> items) {
    }

    public record Status(boolean webhookConfigured, String configuredPortal, List<String> allowedHostSuffixes,
                         int activePlans, BigDecimal activeAmount, java.time.Instant lastSyncedAt) {
    }
}
