--liquibase formatted sql

--changeset orchestrator:012-gap-identities
-- A deterministic Forecast gap id may recur in separate runs of the same input.
alter table forecast_gaps drop constraint forecast_gaps_pkey;

-- Repair the legacy random storage ids using the immutable saved response, not today's calculation.
update forecast_gaps stored
set id = (episode.value ->> 'id')::uuid
from forecasts forecast
cross join lateral jsonb_array_elements(coalesce(forecast.response_json -> 'gaps', '[]'::jsonb)) episode
where stored.forecast_id = forecast.id
  and stored.kind = episode.value ->> 'kind'
  and stored.from_date = (episode.value ->> 'fromDate')::date
  and stored.to_date = (episode.value ->> 'toDate')::date;

alter table forecast_gaps add primary key (forecast_id, id);
alter table forecasts add constraint forecasts_tenant_identity unique (id, company_id, account_id);
alter table forecast_gaps add constraint forecast_gaps_tenant_forecast
    foreign key (forecast_id, company_id, account_id) references forecasts (id, company_id, account_id);

-- Tie-breaking uses enqueue order, never result completion time. Existing inserts use the default.
alter table jobs add column enqueue_sequence bigint generated always as identity;
alter table jobs add constraint jobs_enqueue_sequence_unique unique (enqueue_sequence);
--rollback alter table jobs drop column enqueue_sequence;
--rollback alter table forecast_gaps drop constraint forecast_gaps_tenant_forecast;
--rollback alter table forecasts drop constraint forecasts_tenant_identity;
--rollback alter table forecast_gaps drop constraint forecast_gaps_pkey;
--rollback update forecast_gaps set id = gen_random_uuid();
--rollback alter table forecast_gaps add primary key (id);
