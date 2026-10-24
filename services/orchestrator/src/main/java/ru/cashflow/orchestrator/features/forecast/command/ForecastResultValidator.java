package ru.cashflow.orchestrator.features.forecast.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult.Day;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult.Gap;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult.Risk;

/**
 * Checks a ForecastRs against the job and its immutable snapshot before anything is stored (handoff §6):
 * identities, 30 consecutive days from asOf+1, balance and flow decomposition with exact money arithmetic,
 * probability bounds, unavailable-risk shape, no NaN/Infinity/unknown enums, consistent gaps.
 * Any violation is PERMANENT: the job fails and no fake forecast is stored.
 */
public final class ForecastResultValidator {

    private static final Pattern MONEY = Pattern.compile("^-?[0-9]{1,17}\\.[0-9]{2}$");
    private static final Set<String> QUALITY = Set.of("GOOD", "LIMITED", "INSUFFICIENT");
    private static final Set<String> AVAILABILITY = Set.of("AVAILABLE", "INSUFFICIENT_HISTORY", "INPUT_INCOMPLETE");
    private static final Set<String> GAP_KIND = Set.of("CASH_GAP", "BUFFER_BREACH");
    private static final String BOOTSTRAP = "HISTORICAL_TRAJECTORY_BOOTSTRAP";

    private ForecastResultValidator() {
    }

    public record Expected(UUID resultId, UUID jobId, UUID snapshotId, UUID companyId, UUID accountId,
                           LocalDate asOfDate, String scope, String algorithmVersion, long inputDataVersion,
                           BigDecimal startBalance, LocalDate startBalanceDate, BigDecimal bufferAmount,
                           int minCalibrationOrigins, int calibrationOriginsSent) {
    }

    public static ForecastResult validate(JsonNode rs, Expected e) {
        require(rs != null && rs.isObject(), "BODY", "Ответ не является JSON-объектом");
        require(uuid(rs, "id").equals(e.resultId()), "id", "Результат относится к другому расчёту");
        require(uuid(rs, "jobId").equals(e.jobId()), "jobId", "Результат относится к другому заданию");
        require(uuid(rs, "snapshotId").equals(e.snapshotId()), "snapshotId", "Результат построен по другому snapshot");
        require(uuid(rs, "companyId").equals(e.companyId()), "companyId", "Результат другой компании");
        require(uuid(rs, "accountId").equals(e.accountId()), "accountId", "Результат другого счёта");
        require(date(rs, "asOfDate").equals(e.asOfDate()), "asOfDate", "Не совпадает asOfDate");
        require(integer(rs, "horizonDays") == 30, "horizonDays", "Горизонт должен быть ровно 30 дней");
        require(text(rs, "scope").equals(e.scope()), "scope", "Не совпадает scope");
        require(text(rs, "algorithmVersion").equals(e.algorithmVersion()), "algorithmVersion", "Другая версия алгоритма");
        require(longValue(rs, "inputDataVersion") == e.inputDataVersion(), "inputDataVersion",
                "Не совпадает inputDataVersion snapshot");
        require(money(rs, "startBalance").compareTo(e.startBalance()) == 0, "startBalance", "Другой стартовый B(T)");
        require(date(rs, "startBalanceDate").equals(e.startBalanceDate()), "startBalanceDate", "Другая дата B(T)");
        String quality = text(rs, "quality");
        require(QUALITY.contains(quality), "quality", "Неизвестное значение quality");
        Risk risk = risk(rs.path("riskEstimate"), e);
        boolean riskAvailable = "AVAILABLE".equals(risk.availability());

        JsonNode daysNode = rs.path("days");
        require(daysNode.isArray() && daysNode.size() == 30, "days", "Нужно ровно 30 дней прогноза");
        List<Day> days = new ArrayList<>();
        BigDecimal previous = e.startBalance();
        BigDecimal minimum = null;
        LocalDate firstGap = null;
        for (int i = 0; i < 30; i++) {
            JsonNode d = daysNode.get(i);
            String at = "days[" + i + "]";
            LocalDate date = date(d, "date");
            require(date.equals(e.asOfDate().plusDays(i + 1L)), at + ".date", "Дни должны идти подряд с T+1");
            BigDecimal plannedIn = nonNegative(d, "plannedIn", at);
            BigDecimal plannedOut = nonNegative(d, "plannedOut", at);
            BigDecimal recurringIn = nonNegative(d, "recurringIn", at);
            BigDecimal recurringOut = nonNegative(d, "recurringOut", at);
            BigDecimal residualIn = nonNegative(d, "residualIn", at);
            BigDecimal residualOut = nonNegative(d, "residualOut", at);
            BigDecimal inflow = nonNegative(d, "inflow", at);
            BigDecimal outflow = nonNegative(d, "outflow", at);
            BigDecimal balance = money(d, "balance");
            require(inflow.compareTo(plannedIn.add(recurringIn).add(residualIn)) == 0, at + ".inflow",
                    "inflow ≠ plannedIn + recurringIn + residualIn");
            require(outflow.compareTo(plannedOut.add(recurringOut).add(residualOut)) == 0, at + ".outflow",
                    "outflow ≠ plannedOut + recurringOut + residualOut");
            require(balance.compareTo(previous.add(inflow).subtract(outflow)) == 0, at + ".balance",
                    "balance[d] ≠ balance[d-1] + inflow − outflow");
            BigDecimal p10 = nullableMoney(d, "balanceP10");
            BigDecimal p50 = nullableMoney(d, "balanceP50");
            BigDecimal p90 = nullableMoney(d, "balanceP90");
            BigDecimal pZero = probability(d, "pBelowZero", at);
            BigDecimal pBuffer = probability(d, "pBelowBuffer", at);
            if (!riskAvailable) {
                require(p10 == null && p50 == null && p90 == null && pZero == null && pBuffer == null, at,
                        "При недоступном риске квантили и вероятности должны быть null");
            } else if (p10 != null && p50 != null && p90 != null) {
                require(p10.compareTo(p50) <= 0 && p50.compareTo(p90) <= 0, at, "Квантили не упорядочены");
            }
            if (minimum == null || balance.compareTo(minimum) < 0) {
                minimum = balance;
            }
            if (firstGap == null && balance.signum() < 0) {
                firstGap = date;
            }
            days.add(new Day(date, plannedIn, plannedOut, recurringIn, recurringOut, residualIn, residualOut, inflow,
                    outflow, balance, p10, p50, p90, pZero, pBuffer));
            previous = balance;
        }
        BigDecimal expectedMin = money(rs, "expectedMinimumBalance");
        require(expectedMin.compareTo(minimum) == 0, "expectedMinimumBalance", "Не совпадает минимум точечного баланса");
        LocalDate reportedGap = nullableDate(rs, "firstExpectedGapDate");
        require(java.util.Objects.equals(reportedGap, firstGap), "firstExpectedGapDate",
                "Не совпадает дата первого ожидаемого разрыва");
        BigDecimal shortfall = nonNegative(rs, "maxExpectedShortfall", "");
        BigDecimal expectedShortfall = minimum.signum() < 0 ? minimum.negate() : new BigDecimal("0.00");
        require(shortfall.compareTo(expectedShortfall) == 0, "maxExpectedShortfall", "Не совпадает max shortfall");
        List<Gap> gaps = gaps(rs.path("gaps"), e, days, riskAvailable);
        require(rs.path("warnings").isArray(), "warnings", "warnings должен быть массивом");
        return new ForecastResult(e.resultId(), e.jobId(), e.snapshotId(), e.companyId(), e.accountId(), e.asOfDate(),
                30, e.scope(), e.algorithmVersion(), e.inputDataVersion(), e.startBalance(), e.startBalanceDate(),
                quality, expectedMin, firstGap, shortfall, List.copyOf(days), gaps, risk);
    }

    private static Risk risk(JsonNode r, Expected e) {
        require(r.isObject(), "riskEstimate", "Нет riskEstimate");
        String availability = text(r, "availability");
        require(AVAILABILITY.contains(availability), "riskEstimate.availability", "Неизвестная availability");
        int scenarios = integer(r, "scenarioCount");
        int origins = integer(r, "calibrationOriginCount");
        require(origins >= 0 && origins <= e.calibrationOriginsSent(), "riskEstimate.calibrationOriginCount",
                "Использовано больше calibration origins, чем передано");
        require(r.path("probabilityCalibrated").isBoolean() && !r.path("probabilityCalibrated").asBoolean(),
                "riskEstimate.probabilityCalibrated", "probabilityCalibrated должен быть false");
        BigDecimal probability = probability(r, "probability", "riskEstimate");
        String method = r.path("method").isNull() ? null : text(r, "method");
        if (!"AVAILABLE".equals(availability)) {
            require(probability == null && scenarios == 0, "riskEstimate",
                    "Недоступный риск: probability=null и scenarioCount=0");
        } else {
            require(BOOTSTRAP.equals(method), "riskEstimate.method", "Неизвестный метод оценки риска");
            require(probability != null && scenarios >= 100 && scenarios <= 5000, "riskEstimate",
                    "Доступный риск требует probability и scenarioCount 100..5000");
            require(origins >= e.minCalibrationOrigins(), "riskEstimate.calibrationOriginCount",
                    "Меньше origins, чем minCalibrationOrigins: probability должна быть null");
        }
        return new Risk(availability, method, probability, scenarios, origins, false);
    }

    private static List<Gap> gaps(JsonNode node, Expected e, List<Day> days, boolean riskAvailable) {
        require(node.isArray(), "gaps", "gaps должен быть массивом");
        List<Gap> gaps = new ArrayList<>();
        LocalDate first = e.asOfDate().plusDays(1);
        LocalDate last = e.asOfDate().plusDays(30);
        java.util.Map<String, LocalDate> lastEnd = new java.util.HashMap<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode g = node.get(i);
            String at = "gaps[" + i + "]";
            String kind = text(g, "kind");
            require(GAP_KIND.contains(kind), at + ".kind", "Неизвестный kind разрыва");
            LocalDate from = date(g, "fromDate");
            LocalDate to = date(g, "toDate");
            require(!from.isBefore(first) && !to.isAfter(last) && !from.isAfter(to), at, "Даты разрыва вне горизонта");
            LocalDate previousEnd = lastEnd.get(kind);
            require(previousEnd == null || from.isAfter(previousEnd), at, "Разрывы одного вида пересекаются");
            lastEnd.put(kind, to);
            BigDecimal threshold = money(g, "threshold");
            BigDecimal expected = "CASH_GAP".equals(kind) ? new BigDecimal("0.00") : e.bufferAmount();
            require(threshold.compareTo(expected) == 0, at + ".threshold", "Порог разрыва не соответствует виду");
            BigDecimal min = money(g, "expectedMinimumBalance");
            BigDecimal shortfall = nonNegative(g, "expectedShortfall", at);
            BigDecimal pointMin = days.stream().filter(d -> !d.date().isBefore(from) && !d.date().isAfter(to))
                    .map(Day::balance).min(BigDecimal::compareTo).orElseThrow();
            require(min.compareTo(pointMin) == 0, at + ".expectedMinimumBalance", "Минимум эпизода не совпадает с днями");
            BigDecimal deficit = threshold.subtract(pointMin);
            require(shortfall.compareTo(deficit.signum() > 0 ? deficit : new BigDecimal("0.00")) == 0,
                    at + ".expectedShortfall", "Shortfall эпизода не равен порог − минимум");
            BigDecimal probability = probability(g, "probability", at);
            BigDecimal p50 = nullableMoney(g, "shortfallP50");
            BigDecimal p90 = nullableMoney(g, "shortfallP90");
            String basis = g.path("shortfallBasis").isNull() ? null : text(g, "shortfallBasis");
            String availability = text(g, "riskEstimateAvailability");
            require(AVAILABILITY.contains(availability), at + ".riskEstimateAvailability", "Неизвестная availability");
            if (!riskAvailable) {
                require(probability == null && p50 == null && p90 == null, at,
                        "При недоступном риске вероятность и квантили эпизода — null");
            } else if (p50 != null || p90 != null) {
                require("CONDITIONAL_ON_BREACH".equals(basis), at + ".shortfallBasis", "Неизвестная база shortfall");
            }
            gaps.add(new Gap(uuid(g, "id"), kind, from, to, threshold, min, shortfall, probability, p50, p90, basis,
                    availability));
        }
        return List.copyOf(gaps);
    }

    private static BigDecimal nonNegative(JsonNode n, String field, String at) {
        BigDecimal value = money(n, field);
        require(value.signum() >= 0, (at.isEmpty() ? "" : at + ".") + field, "Значение не может быть отрицательным");
        return value;
    }

    private static BigDecimal money(JsonNode n, String field) {
        JsonNode v = n.path(field);
        require(v.isTextual() && MONEY.matcher(v.asText()).matches(), field, "Деньги — строка с двумя знаками");
        return new BigDecimal(v.asText());
    }

    private static BigDecimal nullableMoney(JsonNode n, String field) {
        require(n.has(field), field, "Поле обязательно (может быть null)");
        return n.path(field).isNull() ? null : money(n, field);
    }

    private static BigDecimal probability(JsonNode n, String field, String at) {
        require(n.has(field), at + "." + field, "Поле обязательно (может быть null)");
        JsonNode v = n.path(field);
        if (v.isNull()) {
            return null;
        }
        require(v.isNumber() && !Double.isNaN(v.asDouble()) && !Double.isInfinite(v.asDouble()), at + "." + field,
                "Вероятность — число");
        BigDecimal p = v.decimalValue();
        require(p.signum() >= 0 && p.compareTo(BigDecimal.ONE) <= 0, at + "." + field, "Вероятность вне [0,1]");
        return p;
    }

    private static UUID uuid(JsonNode n, String field) {
        try {
            return UUID.fromString(text(n, field));
        } catch (IllegalArgumentException exception) {
            throw invalid(field, "Некорректный UUID");
        }
    }

    private static LocalDate date(JsonNode n, String field) {
        try {
            return LocalDate.parse(text(n, field));
        } catch (DateTimeParseException exception) {
            throw invalid(field, "Некорректная дата");
        }
    }

    private static LocalDate nullableDate(JsonNode n, String field) {
        require(n.has(field), field, "Поле обязательно (может быть null)");
        return n.path(field).isNull() ? null : date(n, field);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        require(v.isTextual(), field, "Поле обязательно");
        return v.asText();
    }

    private static int integer(JsonNode n, String field) {
        JsonNode v = n.path(field);
        require(v.isIntegralNumber() && v.canConvertToInt(), field, "Нужно целое число");
        return v.asInt();
    }

    private static long longValue(JsonNode n, String field) {
        JsonNode v = n.path(field);
        require(v.isIntegralNumber() && v.canConvertToLong(), field, "Нужно целое число");
        return v.asLong();
    }

    private static void require(boolean condition, String field, String message) {
        if (!condition) {
            throw invalid(field, message);
        }
    }

    private static ForecastException invalid(String field, String message) {
        return new ForecastException(ForecastException.Kind.PERMANENT, "INVALID_FORECAST_RESULT",
                message + " (" + field + ")", null);
    }
}
