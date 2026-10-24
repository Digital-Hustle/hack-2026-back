package ru.cashflow.orchestrator.features.results.repository;

/** Shared selection for the dashboard and background refresh; a historical result cannot replace the published head. */
public final class ForecastSelectionSql {

    public static final String FROM = "from forecasts f join snapshots s on s.id=f.snapshot_id "
            + "join jobs j on j.id=f.job_id left join forecast_alert_heads h "
            + "on h.company_id=f.company_id and h.account_id=f.account_id ";

    public static final String IS_LATEST = "case when h.forecast_id is not null then h.forecast_id=f.id "
            + "else not exists (select 1 from forecasts newer join snapshots ns on ns.id=newer.snapshot_id "
            + "join jobs nj on nj.id=newer.job_id where newer.company_id=f.company_id and newer.account_id=f.account_id "
            + "and (newer.as_of,newer.input_version,ns.created_at,nj.created_at,nj.enqueue_sequence) > "
            + "(f.as_of,f.input_version,s.created_at,j.created_at,j.enqueue_sequence)) end";

    public static final String ORDER_BY = IS_LATEST + " desc,f.as_of desc,f.input_version desc,s.created_at desc,"
            + "j.created_at desc,j.enqueue_sequence desc";

    private ForecastSelectionSql() {
    }
}
