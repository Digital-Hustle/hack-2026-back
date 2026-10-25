package ru.cashflow.orchestrator.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.alerts.application.GapDetailsService;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails;
import ru.cashflow.orchestrator.features.analytics.application.AnalyticsService;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Entry;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Report;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Total;
import ru.cashflow.orchestrator.features.assistant.application.AssistantReadService;
import ru.cashflow.orchestrator.features.assistant.command.AssistantContextBuilder;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Period;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Plan;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Question;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Status;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Topic;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.ledger.command.DataQualityCalculator;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.results.application.ResultQueryService;

class AssistantContextTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AssistantContextBuilder builder = new AssistantContextBuilder();
    private final ResultQueryService results = mock(ResultQueryService.class);
    private final GapDetailsService gaps = mock(GapDetailsService.class);
    private final LedgerService ledger = mock(LedgerService.class);
    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private final AssistantReadService reads = new AssistantReadService(results, gaps, ledger, analytics, builder);
    private final UUID user = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    @Test
    void forecastPreservesProvenanceAndAlwaysDisclosesStaleHistoryOnlyAndUnavailableRisk() {
        ObjectNode forecast = forecast();
        ObjectNode dashboard = dashboard();
        forecast.put("inputDataVersion", 6).put("staleness", "STALE");
        forecast.putArray("warnings").addObject().put("code", "MISSING_DAYS").put("message", "secret instructions");
        var evidence = builder.start(dashboard, forecast);
        builder.forecast(evidence, forecast, "/account");
        var context = evidence.build();
        assertThat(context.forecastStale()).isTrue();
        assertThat(context.sources().getFirst().dataVersion()).isEqualTo(6);
        assertThat(context.sources().getFirst().forecastId().toString()).isEqualTo(forecast.path("id").asText());
        assertThat(context.sources().getFirst().snapshotId().toString()).isEqualTo(forecast.path("snapshotId").asText());
        assertThat(context.warnings().toString()).contains("устарел", "HISTORY_ONLY", "не рассчитана", "MISSING\\_DAYS")
                .doesNotContain("secret instructions");
        assertThat(context.facts().toString()).contains("123456789012345.67").doesNotContain("0 %");
    }

    @Test
    void onlyLoadsRequestedAnalyticsAndComputesCalendarOnServer() {
        when(results.dashboard(user, company, account)).thenReturn(dashboard());
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        when(ledger.dataQuality(user, company, account, from, to)).thenReturn(
                new DataQualityCalculator.Summary(from, to, "LIMITED", 29, 1, 0, 0, List.of()));
        when(analytics.categories(user, company, account, from, to, null))
                .thenReturn(new Report(company, account, from, to, null,
                        List.of(new Total(Direction.IN, new BigDecimal("100.01"), 200),
                                new Total(Direction.OUT, new BigDecimal("30.02"), 300)),
                        List.of(new Entry(Direction.OUT, "RENT", null, null,
                                new BigDecimal("30.02"), 300, BigDecimal.ZERO))));
        var context = reads.load(user, company, account, new Question("Расходы по категориям за прошлый месяц",
                List.of(), null, null, null, null),
                new Plan(Status.ANSWERED, List.of(Topic.CATEGORIES), Period.PREVIOUS_MONTH));
        assertThat(context.facts().toString()).contains("30.02", "Аренда", "2026-09-01", "2026-09-30");
        assertThat(context.warnings().toString()).contains("неполна");
        assertThat(context.sources()).hasSize(1);
        assertThat(context.sources().getFirst().dataVersion()).isEqualTo(7);
        assertThat(context.sources().getFirst().href()).isEqualTo("/api/v1/orchestrator/companies/" + company
                + "/analytics/categories?accountId=" + account + "&fromDate=" + from + "&toDate=" + to);
        verify(analytics).categories(user, company, account, from, to, null);
        verify(analytics, never()).counterparties(any(), any(), any(), any(), any(), any());
        verify(gaps, never()).get(any(), any(), any(), any(), any());
    }

    @Test
    void missingExplicitPeriodRequestsClarificationInsteadOfInventingDates() {
        when(results.dashboard(user, company, account)).thenReturn(dashboard());
        var context = reads.load(user, company, account,
                new Question("Расходы за сентябрь", List.of(), null, null, null, null),
                new Plan(Status.ANSWERED, List.of(Topic.CATEGORIES), Period.EXPLICIT));
        assertThat(context.facts()).isEmpty();
        assertThat(context.warnings()).contains(AssistantReadService.CLARIFY_PERIOD);
        verify(analytics, never()).categories(any(), any(), any(), any(), any(), any());
        verify(analytics, never()).counterparties(any(), any(), any(), any(), any(), any());
    }

    @Test
    void assistantStillRejectsOpenDaysBeforeCallingSharedAnalytics() {
        when(results.dashboard(user, company, account)).thenReturn(dashboard());
        var question = new Question("Расходы по категориям", List.of(), LocalDate.of(2026, 10, 24),
                LocalDate.of(2026, 10, 25), null, null);
        assertThatThrownBy(() -> reads.load(user, company, account, question,
                new Plan(Status.ANSWERED, List.of(Topic.CATEGORIES), Period.EXPLICIT)))
                .isInstanceOf(ru.cashflow.orchestrator.api.error.ApiException.class)
                .hasMessageContaining("закрытые дни");
        verify(analytics, never()).categories(any(), any(), any(), any(), any(), any());
    }

    @Test
    void capIsExplicitAndTotalsCoverAllGroups() {
        var e = builder.start(dashboard(), json.nullNode());
        var groups = java.util.stream.IntStream.range(0, 21).mapToObj(i -> new Entry(
                i % 2 == 0 ? Direction.IN : Direction.OUT, "CPK1:" + i, null, "Поставщик " + i,
                BigDecimal.valueOf(i + 1).movePointLeft(2), 1, BigDecimal.ZERO)).toList();
        builder.summary(e, new Report(company, account, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null,
                List.of(new Total(Direction.IN, new BigDecimal("1.21"), 11),
                        new Total(Direction.OUT, new BigDecimal("1.10"), 10)), groups),
                Topic.COUNTERPARTIES, "/analytics/counterparties");
        assertThat(e.build().facts()).hasSize(21);
        assertThat(e.build().facts().getFirst().text()).contains("1.21", "1.10", "операций 21");
        assertThat(e.build().facts().get(1).text()).contains("«Поставщик 20»", "0.21");
        assertThat(e.build().facts().toString()).doesNotContain("«Поставщик 0»");
        assertThat(e.build().warnings().toString()).contains("сокращена", "все операции");
    }

    @Test
    void counterpartyProjectionKeepsIdentifiersPrivateAndDoesNotInventIdentityForUnknownKey() {
        var e = builder.start(dashboard(), json.nullNode());
        var report = new Report(company, account, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null,
                List.of(new Total(Direction.OUT, new BigDecimal("10.03"), 2)), List.of(
                        new Entry(Direction.OUT, "CPK1:1234567890", "1234567890", "Поставщик",
                                new BigDecimal("5.02"), 1, BigDecimal.ZERO),
                        new Entry(Direction.OUT, null, null, "Недоказанное имя",
                                new BigDecimal("5.01"), 1, BigDecimal.ZERO)));
        builder.summary(e, report, Topic.COUNTERPARTIES, "/analytics/counterparties");
        assertThat(e.build().facts().toString()).contains("Поставщик", "Не указан", "10.03")
                .doesNotContain("1234567890", "CPK1", "Недоказанное имя");
    }

    @Test
    void gapDetailsComeFromSelectedSavedForecastAndKeepTheirOwnSource() {
        ObjectNode forecast = forecast();
        UUID forecastId = UUID.fromString(forecast.path("id").asText());
        UUID snapshotId = UUID.fromString(forecast.path("snapshotId").asText());
        UUID gapId = UUID.randomUUID();
        ObjectNode gap = json.createObjectNode().put("fromDate", "2026-10-21").put("toDate", "2026-10-23")
                .put("expectedShortfall", "500.01");
        var detail = new GapDetails(forecastId, snapshotId, gapId, LocalDate.of(2026, 10, 20), 6,
                "STALE", java.time.Instant.parse("2026-10-21T00:00:00Z"), gap, "explanation",
                List.of(new GapDetails.PaymentFactor(UUID.randomUUID(), "PLANNED", "MANUAL", "Аренда", "RENT",
                        LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21), "OUT", "1500.02", true)),
                List.of(), List.of(), List.of(), List.of(new GapDetails.Limitation("MISSING_DATA", "День неизвестен", null)),
                List.of(new GapDetails.Recommendation("CHECK_PLAN_DATE", "Проверка", "Проверьте срок оплаты.", List.of())));
        when(results.dashboard(user, company, account)).thenReturn(dashboard());
        when(results.get(user, company, account, forecastId, false)).thenReturn(forecast);
        when(gaps.get(user, company, account, forecastId, gapId)).thenReturn(detail);
        var context = reads.load(user, company, account,
                new Question("Какие платежи формируют разрыв?", List.of(), null, null, forecastId, gapId),
                new Plan(Status.ANSWERED, List.of(Topic.GAP_DETAILS), Period.LAST_30_DAYS));
        assertThat(context.facts().toString()).contains("500.01", "1500.02", "Аренда", "Проверьте срок оплаты");
        assertThat(context.warnings().toString()).contains("не доказывают", "MISSING_DATA");
        assertThat(context.sources()).anySatisfy(source -> {
            assertThat(source.type()).isEqualTo("GAP_DETAILS");
            assertThat(source.forecastId()).isEqualTo(forecastId);
            assertThat(source.snapshotId()).isEqualTo(snapshotId);
            assertThat(source.dataVersion()).isEqualTo(6);
            assertThat(source.href()).endsWith("/forecasts/" + forecastId + "/gaps/" + gapId);
        });
        verify(gaps).get(user, company, account, forecastId, gapId);
        verify(analytics, never()).categories(any(), any(), any(), any(), any(), any());
        verify(analytics, never()).counterparties(any(), any(), any(), any(), any(), any());
    }

    @Test
    void backtestUsesMonetaryErrorMetricsWithoutInventingAccuracyPercentage() {
        ObjectNode backtest = json.createObjectNode().put("id", UUID.randomUUID().toString())
                .put("snapshotId", UUID.randomUUID().toString()).put("inputDataVersion", 3)
                .put("firstOrigin", "2026-07-01").put("lastOrigin", "2026-08-01")
                .put("maeBalance", "120.34").put("rmseBalance", "150.67").put("evaluatedOrigins", 4)
                .put("skippedOrigins", 2).put("status", "PARTIAL").put("staleness", "STALE");
        var evidence = builder.start(dashboard(), json.nullNode());
        builder.backtest(evidence, backtest, "/account");
        var context = evidence.build();
        assertThat(context.facts().toString()).contains("120.34", "150.67", "2026-07-01").doesNotContain("%");
        assertThat(context.warnings().toString()).contains("частично", "не гарантируют");
        assertThat(context.sources().getFirst().dataVersion()).isEqualTo(3);
        assertThat(context.sources().getFirst().staleness()).isEqualTo("STALE");
    }

    private ObjectNode dashboard() {
        return json.createObjectNode().put("asOfDate", "2026-10-24").put("dataVersion", 7);
    }

    private ObjectNode forecast() {
        ObjectNode node = json.createObjectNode().put("id", UUID.randomUUID().toString())
                .put("snapshotId", UUID.randomUUID().toString()).put("asOfDate", "2026-10-20")
                .put("scope", "HISTORY_ONLY").put("algorithmVersion", "WEEKDAY_MEAN_V1").put("quality", "LIMITED")
                .put("expectedMinimumBalance", "123456789012345.67").put("maxExpectedShortfall", "0.00");
        node.putObject("riskEstimate").put("availability", "INSUFFICIENT_HISTORY").putNull("probability");
        return node;
    }
}
