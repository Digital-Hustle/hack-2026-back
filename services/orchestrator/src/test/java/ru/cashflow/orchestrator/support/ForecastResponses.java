package ru.cashflow.orchestrator.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds a ForecastRs that a correct WEEKDAY_MEAN_V1 point engine would return for a snapshot with a constant daily
 * net flow and no risk. Tests then mutate it to check that each validation rule rejects a bad result.
 */
public final class ForecastResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ForecastResponses() {
    }

    public static ObjectNode valid(JsonNode request, UUID resultId, UUID jobId, String dailyIn, String dailyOut) {
        LocalDate asOf = LocalDate.parse(request.path("asOfDate").asText());
        BigDecimal balance = new BigDecimal(request.path("startBalance").asText());
        BigDecimal in = new BigDecimal(dailyIn);
        BigDecimal out = new BigDecimal(dailyOut);
        ObjectNode rs = JSON.createObjectNode();
        rs.put("id", resultId.toString());
        rs.put("jobId", jobId.toString());
        rs.put("snapshotId", request.path("snapshotId").asText());
        rs.put("companyId", request.path("companyId").asText());
        rs.put("accountId", request.path("accountId").asText());
        rs.put("asOfDate", asOf.toString());
        rs.put("horizonDays", 30);
        rs.put("scope", request.path("scope").asText());
        rs.put("algorithm", "WEEKDAY_MEAN_V1");
        rs.put("algorithmVersion", request.path("algorithmVersion").asText());
        rs.set("settings", request.path("settings"));
        rs.put("inputDataVersion", request.path("inputDataVersion").asLong());
        rs.put("startBalance", request.path("startBalance").asText());
        rs.put("startBalanceDate", request.path("startBalanceDate").asText());
        rs.put("quality", "LIMITED");
        rs.set("warnings", JSON.createArrayNode());
        ObjectNode risk = rs.putObject("riskEstimate");
        risk.put("availability", "INSUFFICIENT_HISTORY");
        risk.putNull("method");
        risk.putNull("probability");
        risk.put("scenarioCount", 0);
        risk.put("calibrationOriginCount", 0);
        risk.put("probabilityCalibrated", false);
        risk.set("assumptions", JSON.createArrayNode());
        ArrayNode days = rs.putArray("days");
        BigDecimal min = null;
        String firstGap = null;
        for (int i = 1; i <= 30; i++) {
            balance = balance.add(in).subtract(out);
            ObjectNode d = days.addObject();
            d.put("date", asOf.plusDays(i).toString());
            d.put("plannedIn", "0.00");
            d.put("plannedOut", "0.00");
            d.put("recurringIn", "0.00");
            d.put("recurringOut", "0.00");
            d.put("residualIn", in.toPlainString());
            d.put("residualOut", out.toPlainString());
            d.put("inflow", in.toPlainString());
            d.put("outflow", out.toPlainString());
            d.put("balance", balance.toPlainString());
            d.putNull("balanceP10");
            d.putNull("balanceP50");
            d.putNull("balanceP90");
            d.putNull("pBelowZero");
            d.putNull("pBelowBuffer");
            if (min == null || balance.compareTo(min) < 0) {
                min = balance;
            }
            if (firstGap == null && balance.signum() < 0) {
                firstGap = asOf.plusDays(i).toString();
            }
        }
        rs.put("expectedMinimumBalance", min.toPlainString());
        if (firstGap == null) {
            rs.putNull("firstExpectedGapDate");
        } else {
            rs.put("firstExpectedGapDate", firstGap);
        }
        rs.put("maxExpectedShortfall", min.signum() < 0 ? min.negate().toPlainString() : "0.00");
        rs.set("gaps", JSON.createArrayNode());
        rs.put("staleness", "FRESH");
        rs.put("createdAt", "2026-10-25T09:00:00Z");
        return rs;
    }
}
