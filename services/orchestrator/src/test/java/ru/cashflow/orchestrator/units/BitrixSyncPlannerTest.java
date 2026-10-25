package ru.cashflow.orchestrator.units;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.integrations.bitrix.application.BitrixSyncPlanner;
import ru.cashflow.orchestrator.features.integrations.bitrix.application.BitrixSyncPlanner.Decision;
import ru.cashflow.orchestrator.features.integrations.bitrix.gateway.BitrixDemoData;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Action;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.DocumentKind;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Mode;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Stage;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncOptions;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BitrixSyncPlannerTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final LocalDate TO = TODAY.plusDays(30);
    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-26T09:00:00Z");

    @Test
    void createsIncomingPlansOnlyForOpenRubDocumentsInsideHorizon() {
        var decisions = plan(BitrixDemoData.documents(TODAY), List.of(), options(false));

        assertThat(count(decisions, Action.CREATED)).isEqualTo(5);
        assertThat(count(decisions, Action.SKIPPED)).isEqualTo(5);
        assertThat(reason(decisions, "demo/deal/105")).isEqualTo("WON");
        assertThat(reason(decisions, "demo/deal/104")).isEqualTo("LOST");
        assertThat(reason(decisions, "demo/deal/106")).isEqualTo("CURRENCY_NOT_RUB");
        assertThat(reason(decisions, "demo/invoice/203")).isEqualTo("WON");
        assertThat(reason(decisions, "demo/invoice/204")).isEqualTo("LOST");

        ExpectedPayment deal = next(decisions, "demo/deal/101");
        assertThat(deal.direction().name()).isEqualTo("IN");
        assertThat(deal.amount()).isEqualByComparingTo("350000.00");
        assertThat(deal.dueDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(deal.source()).isEqualTo(PlanSource.CONTRACT);
        assertThat(deal.relation().name()).isEqualTo("ADDITIONAL");
        assertThat(deal.categoryCode()).isEqualTo("SALES");
        assertThat(deal.counterpartyKey()).isEqualTo("bitrix24:company:11");
        assertThat(deal.sourceDocument().externalSystem()).isEqualTo("BITRIX24");
        assertThat(deal.status()).isEqualTo(PlanStatus.OPEN);
        assertThat(deal.version()).isEqualTo(0L);
        assertThat(next(decisions, "demo/invoice/201").source()).isEqualTo(PlanSource.INVOICE);
        assertThat(next(decisions, "demo/invoice/201").sourceDocument().documentNumber()).isEqualTo("2026-118");
    }

    @Test
    void probabilityWeightingAppliesOnlyToDeals() {
        var decisions = plan(BitrixDemoData.documents(TODAY), List.of(), options(true));

        assertThat(next(decisions, "demo/deal/101").amount()).isEqualByComparingTo("280000.00");
        assertThat(next(decisions, "demo/deal/103").amount()).isEqualByComparingTo("28800.00");
        assertThat(next(decisions, "demo/invoice/201").amount()).isEqualByComparingTo("64000.00");
        assertThat(next(decisions, "demo/deal/101").note()).contains("учтена с вероятностью");
    }

    @Test
    void repeatedSyncOfSameDataChangesNothing() {
        var documents = BitrixDemoData.documents(TODAY);
        var synced = created(plan(documents, List.of(), options(false)));

        var repeat = plan(documents, synced, options(false));

        assertThat(count(repeat, Action.UNCHANGED)).isEqualTo(5);
        assertThat(repeat.stream().filter(Decision::changes).count()).isEqualTo(0L);
    }

    @Test
    void changedDocumentUpdatesPlanAndKeepsIdentity() {
        var synced = created(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN)), List.of(),
                options(false)));

        var decisions = plan(List.of(deal("1", "150.00", TODAY.plusDays(4), Stage.OPEN)), synced, options(false));

        Decision update = only(decisions);
        assertThat(update.item().action()).isEqualTo(Action.UPDATED);
        assertThat(update.next().id()).isEqualTo(synced.get(0).id());
        assertThat(update.next().amount()).isEqualByComparingTo("150.00");
        assertThat(update.next().dueDate()).isEqualTo(TODAY.plusDays(4));
        assertThat(update.next().version()).isEqualTo(1L);
        assertThat(update.next().knownAt()).isEqualTo(NOW);
        assertThat(update.next().updatedAt()).isEqualTo(LATER);
    }

    @Test
    void wonOrLostDocumentCancelsItsPlan() {
        var synced = created(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN)), List.of(),
                options(false)));

        Decision won = only(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.WON)), synced, options(false)));

        assertThat(won.item().action()).isEqualTo(Action.CANCELLED);
        assertThat(won.item().reason()).isEqualTo("WON");
        assertThat(won.item().planAmount()).isNull();
        assertThat(won.next().status()).isEqualTo(PlanStatus.CANCELLED);
    }

    @Test
    void cancelledPlanIsReopenedWhenDocumentIsOpenAgain() {
        var synced = created(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN)), List.of(),
                options(false)));
        var cancelled = List.of(only(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.LOST)), synced,
                options(false))).next());

        Decision reopened = only(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN)), cancelled,
                options(false)));

        assertThat(reopened.item().action()).isEqualTo(Action.REOPENED);
        assertThat(reopened.next().status()).isEqualTo(PlanStatus.OPEN);
        assertThat(reopened.next().version()).isEqualTo(2L);
    }

    @Test
    void missingDocumentCancelsOnlyOwnPlansInsideHorizonOfSyncedKinds() {
        var synced = new ArrayList<>(created(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN),
                invoice("7", "50.00", TODAY.plusDays(5))), List.of(), options(false))));
        synced.add(copy(created(plan(List.of(deal("2", "70.00", TODAY.plusDays(4), Stage.OPEN)), List.of(),
                options(false))).get(0), OTHER_ACCOUNT, TODAY.plusDays(4)));
        synced.add(copy(created(plan(List.of(deal("3", "80.00", TODAY.plusDays(6), Stage.OPEN)), List.of(),
                options(false))).get(0), ACCOUNT, TODAY.minusDays(2)));

        var onlyDeals = new SyncOptions(Mode.DEMO, null, 30, true, false, false, "SALES", false);
        var decisions = plan(List.of(), synced, onlyDeals);

        assertThat(decisions).hasSize(1);
        assertThat(only(decisions).item().externalId()).isEqualTo("demo/deal/1");
        assertThat(only(decisions).item().reason()).isEqualTo("NOT_IN_BITRIX");
        assertThat(only(decisions).next().status()).isEqualTo(PlanStatus.CANCELLED);
    }

    @Test
    void planOfSameDocumentOnAnotherAccountIsNeverTouched() {
        var other = List.of(copy(created(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN)), List.of(),
                options(false))).get(0), OTHER_ACCOUNT, TODAY.plusDays(3)));

        Decision decision = only(plan(List.of(deal("1", "200.00", TODAY.plusDays(3), Stage.OPEN)), other,
                options(false)));

        assertThat(decision.item().action()).isEqualTo(Action.SKIPPED);
        assertThat(decision.item().reason()).isEqualTo("OTHER_ACCOUNT");
        assertThat(decision.changes()).isFalse();
    }

    @Test
    void planIsNeverLoweredBelowAllocatedPayments() {
        ExpectedPayment plan = created(plan(List.of(deal("1", "100.00", TODAY.plusDays(3), Stage.OPEN)), List.of(),
                options(false))).get(0);
        ExpectedPayment settled = new ExpectedPayment(plan.id(), plan.companyId(), plan.accountId(), plan.title(),
                plan.direction(), plan.amount(), plan.dueDate(), plan.categoryCode(), plan.counterpartyKey(),
                plan.note(), plan.relation(), null, null, plan.source(), plan.sourceDocument(),
                PlanStatus.PARTIALLY_SETTLED, new BigDecimal("60.00"), plan.knownAt(), plan.createdAt(),
                plan.updatedAt(), 3);

        Decision lower = only(plan(List.of(deal("1", "50.00", TODAY.plusDays(3), Stage.OPEN)), List.of(settled),
                options(false)));
        Decision higher = only(plan(List.of(deal("1", "120.00", TODAY.plusDays(3), Stage.OPEN)), List.of(settled),
                options(false)));

        assertThat(lower.item().reason()).isEqualTo("AMOUNT_BELOW_SETTLED");
        assertThat(lower.changes()).isFalse();
        assertThat(higher.item().action()).isEqualTo(Action.UPDATED);
        assertThat(higher.next().status()).isEqualTo(PlanStatus.PARTIALLY_SETTLED);
        assertThat(higher.next().settledAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    void documentsWithoutAmountOrDateOrOutsideHorizonAreSkipped() {
        var decisions = plan(List.of(deal("1", null, TODAY.plusDays(3), Stage.OPEN),
                deal("2", "10.00", null, Stage.OPEN), deal("3", "10.00", TO.plusDays(1), Stage.OPEN)), List.of(),
                options(false));

        assertThat(reason(decisions, "demo/deal/1")).isEqualTo("NO_AMOUNT");
        assertThat(reason(decisions, "demo/deal/2")).isEqualTo("NO_DUE_DATE");
        assertThat(reason(decisions, "demo/deal/3")).isEqualTo("OUT_OF_HORIZON");
    }

    private static List<Decision> plan(List<BitrixDocument> documents, List<ExpectedPayment> synced,
                                       SyncOptions options) {
        Instant now = synced.isEmpty() ? NOW : LATER;
        return BitrixSyncPlanner.plan(COMPANY, ACCOUNT, "demo", documents, synced, options, TODAY, TO, now);
    }

    private static SyncOptions options(boolean weight) {
        return new SyncOptions(Mode.DEMO, null, 30, true, true, weight, "SALES", false);
    }

    private static List<ExpectedPayment> created(List<Decision> decisions) {
        return decisions.stream().filter(Decision::changes).map(Decision::next).toList();
    }

    private static ExpectedPayment copy(ExpectedPayment p, UUID account, LocalDate due) {
        return new ExpectedPayment(p.id(), p.companyId(), account, p.title(), p.direction(), p.amount(), due,
                p.categoryCode(), p.counterpartyKey(), p.note(), p.relation(), null, null, p.source(),
                p.sourceDocument(), p.status(), p.settledAmount(), p.knownAt(), p.createdAt(), p.updatedAt(),
                p.version());
    }

    private static BitrixDocument deal(String id, String amount, LocalDate due, Stage stage) {
        return new BitrixDocument(DocumentKind.DEAL, id, "Сделка " + id, amount == null ? null : new BigDecimal(amount),
                "RUB", due, TODAY.minusDays(10), stage, "C1:X", 50, "5", "ООО Тест", id);
    }

    private static BitrixDocument invoice(String id, String amount, LocalDate due) {
        return new BitrixDocument(DocumentKind.INVOICE, id, "Счёт " + id, new BigDecimal(amount), "RUB", due,
                TODAY.minusDays(1), Stage.OPEN, "DT31_1:N", null, null, null, "N-" + id);
    }

    private static long count(List<Decision> decisions, Action action) {
        return decisions.stream().filter(d -> d.item().action() == action).count();
    }

    private static String reason(List<Decision> decisions, String externalId) {
        return decisions.stream().filter(d -> d.item().externalId().equals(externalId)).findFirst().orElseThrow()
                .item().reason();
    }

    private static ExpectedPayment next(List<Decision> decisions, String externalId) {
        return decisions.stream().filter(d -> d.item().externalId().equals(externalId)).findFirst().orElseThrow()
                .next();
    }

    private static Decision only(List<Decision> decisions) {
        assertThat(decisions).hasSize(1);
        return decisions.get(0);
    }
}
