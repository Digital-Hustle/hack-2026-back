package ru.cashflow.orchestrator.units;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.cashflow.orchestrator.features.results.application.ResultExports;

class ResultExportsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void traceTextListsStepsAndDataAndExplainsMissingTrace() throws Exception {
        var result = json.readTree("""
                {"id":"r1","algorithmVersion":"WEEKDAY_MEAN_V1","inputDataVersion":3,"snapshotId":"s1",
                 "createdAt":"2026-10-25T10:00:00Z","asOfDate":"2026-10-24",
                 "trace":[{"seq":1,"stage":"VALIDATE","message":"Snapshot accepted","data":{"payments":"14"}}]}
                """);
        String text = ResultExports.traceText(result, "Forecast");
        assertThat(text).contains("[001] VALIDATE").contains("payments = 14").contains("asOfDate: 2026-10-24");
        assertThat(ResultExports.traceText(json.readTree("{\"id\":\"r2\"}"), "Forecast")).contains("not available");
    }

    @Test
    void backtestCsvHasOneRowPerOriginDayWithError() throws Exception {
        var result = json.readTree("""
                {"origins":[{"asOfDate":"2026-09-01","status":"EVALUATED","days":[
                  {"date":"2026-09-02","horizonDay":1,"forecastBalance":"90.00","actualBalance":"100.00",
                   "forecastInflow":"0.00","forecastOutflow":"10.00","actualInflow":"0.00","actualOutflow":"0.00",
                   "balanceP10":null,"balanceP90":null,"pBelowZero":null,"predictedBreach":false,"actualBreach":false}]},
                  {"asOfDate":"2026-09-08","status":"SKIPPED","days":[]}]}
                """);
        String csv = ResultExports.backtestCsv(result);
        assertThat(csv.lines().count()).isEqualTo(3);
        assertThat(csv).contains("2026-09-01,EVALUATED,2026-09-02,1,90.00,100.00,-10.00,0.00,10.00,0.00,0.00,,,,false,false");
        assertThat(csv).contains("2026-09-08,SKIPPED,");
    }

    @Test
    void reportRendersConfusionMatrixAndFallsBackForOldResults() throws Exception {
        var matrix = "{\"threshold\":\"0.00\",\"truePositive\":1,\"falsePositive\":2,\"falseNegative\":3,\"trueNegative\":4,"
                + "\"accuracy\":0.5,\"precision\":0.3333,\"recall\":0.25,\"specificity\":0.6667,\"f1\":0.2857,"
                + "\"balancedAccuracy\":0.4583,\"prevalence\":0.4,\"majorityBaselineAccuracy\":0.6}";
        var result = json.readTree("{\"id\":\"b1\",\"maeBalance\":\"1.00\",\"horizons\":[],\"gapMetrics\":{},"
                + "\"riskMetrics\":{\"calibrationBins\":[]},\"warnings\":[],\"quality\":{\"primary\":{\"model\":\"M\","
                + "\"evaluatedDays\":10,\"maeBalance\":\"1.00\",\"dailyMatrix\":" + matrix + "},\"benchmarks\":[],"
                + "\"originMatrix\":" + matrix + ",\"scenarioDailyMatrix\":null,\"bufferDailyMatrix\":null,"
                + "\"scenarioThreshold\":0.3,\"selection\":null,\"notes\":[\"n1\"]}}");
        String report = ResultExports.backtestReport(result);
        assertThat(report).contains("TP = 1").contains("TN = 4").contains("F1 0.2857").contains("- n1");
        assertThat(ResultExports.backtestReport(json.readTree("{\"maeBalance\":\"5.00\",\"rmseBalance\":\"6.00\"}")))
                .contains("MAE=5.00");
    }
}
