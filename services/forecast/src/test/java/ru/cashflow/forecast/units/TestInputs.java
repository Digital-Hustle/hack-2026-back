package ru.cashflow.forecast.units;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mapstruct.factory.Mappers;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.api.json.ForecastJson;
import ru.cashflow.forecast.api.mapper.ForecastMapper;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.forecast.application.HistoryPreparer;
import ru.cashflow.forecast.features.forecast.application.PlanResolver;
import ru.cashflow.forecast.features.forecast.application.PointForecastEngine;
import ru.cashflow.forecast.features.forecast.application.RecurringCalendar;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;

public final class TestInputs {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("Europe/Moscow"));
    public static final ObjectMapper JSON = ForecastJson.mapper();
    public static final ForecastMapper MAPPER = Mappers.getMapper(ForecastMapper.class);

    private TestInputs() { }

    public static PointForecastEngine engine() {
        var calendar = new RecurringCalendar();
        return new PointForecastEngine(new SnapshotValidator(CLOCK), new HistoryPreparer(),
                new ResidualExtractor(calendar), new PlanResolver(calendar));
    }

    public static ObjectNode fixture(String name) throws IOException {
        try (var stream = TestInputs.class.getResourceAsStream("/fixtures/" + name)) {
            return (ObjectNode) JSON.readTree(stream);
        }
    }

    public static ForecastInput point(Consumer<ObjectNode> change) throws IOException {
        ObjectNode node = fixture("calculate.json");
        change.accept(node);
        return MAPPER.toPointInput(JSON.treeToValue(node, ForecastSnapshot.class));
    }

    public static ForecastInput point() throws IOException {
        return point(node -> { });
    }

    public static ObjectNode synthetic(LocalDate cutoff, int dayCount, String inflow, String outflow) throws IOException {
        ObjectNode node = fixture("calculate.json");
        node.put("asOfDate", cutoff.toString());
        node.put("historyFrom", cutoff.minusDays(dayCount - 1L).toString());
        node.put("startBalanceDate", cutoff.toString());
        node.put("scope", "HISTORY_ONLY");
        node.withArray("knownPlans").removeAll();
        node.withArray("completeRanges").removeAll();
        var days = node.withArray("dailyHistory").removeAll();
        var payments = node.withArray("payments").removeAll();
        BigDecimal net = new BigDecimal(inflow).subtract(new BigDecimal(outflow));
        for (int offset = dayCount - 1; offset >= 0; offset--) {
            LocalDate date = cutoff.minusDays(offset);
            ObjectNode day = days.addObject();
            day.put("date", date.toString());
            day.put("inflow", inflow);
            day.put("outflow", outflow);
            day.put("balanceEod", new BigDecimal("100000.00").subtract(net.multiply(BigDecimal.valueOf(offset))).toPlainString());
            int count = 0;
            for (String direction : java.util.List.of("IN", "OUT")) {
                String amount = direction.equals("IN") ? inflow : outflow;
                if (new BigDecimal(amount).signum() > 0) {
                    ObjectNode payment = payments.addObject();
                    payment.put("paymentId", UUID.nameUUIDFromBytes((date + direction).getBytes(StandardCharsets.UTF_8)).toString());
                    payment.put("date", date.toString());
                    payment.put("amount", amount);
                    payment.put("direction", direction);
                    payment.put("categoryCode", direction.equals("IN") ? "SALES" : "SUPPLIERS");
                    payment.put("counterpartyKey", direction.equals("IN") ? "client-a" : "supplier-a");
                    count++;
                }
            }
            day.put("paymentCount", count);
            day.put("status", count == 0 ? "ZERO_CONFIRMED" : "OBSERVED");
            day.put("balanceMismatch", false);
        }
        return node;
    }

    public static ForecastInput point(ObjectNode node) throws IOException {
        return MAPPER.toPointInput(JSON.treeToValue(node, ForecastSnapshot.class));
    }
}
