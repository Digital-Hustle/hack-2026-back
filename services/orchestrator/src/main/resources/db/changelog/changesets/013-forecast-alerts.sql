--liquibase formatted sql

--changeset orchestrator:013-forecast-alerts
create table forecast_alert_heads (
    company_id uuid not null,
    account_id uuid not null,
    forecast_id uuid not null,
    primary key (company_id, account_id),
    foreign key (forecast_id, company_id, account_id) references forecasts (id, company_id, account_id)
);

create table alerts (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    forecast_id uuid not null,
    gap_id uuid null,
    plan_id uuid null,
    event_key varchar(256) not null,
    code varchar(32) not null check (code in ('CASH_GAP', 'BUFFER_BREACH', 'OVERDUE_PLAN', 'DATA_QUALITY')),
    title varchar(200) not null,
    message varchar(1000) not null,
    from_date date null,
    to_date date null,
    amount numeric(19, 2) null check (amount >= 0),
    probability numeric null check (probability between 0 and 1),
    active boolean not null default false,
    acknowledged_at timestamptz null,
    acknowledged_by uuid null,
    created_at timestamptz not null,
    unique (forecast_id, event_key),
    check ((code in ('CASH_GAP', 'BUFFER_BREACH')) = (gap_id is not null)),
    check ((code = 'OVERDUE_PLAN') = (plan_id is not null)),
    check ((acknowledged_at is null) = (acknowledged_by is null)),
    check (from_date is null or to_date is null or from_date <= to_date),
    foreign key (forecast_id, company_id, account_id) references forecasts (id, company_id, account_id),
    foreign key (forecast_id, gap_id) references forecast_gaps (forecast_id, id)
);

create index alerts_account on alerts (company_id, account_id, created_at desc, id);
create index alerts_active_account on alerts (company_id, account_id) where active;

-- Upgrade previously saved results: they remain readable without calling Forecast again.
insert into forecast_alert_heads (company_id, account_id, forecast_id)
select company_id, account_id, id from (
    select f.company_id, f.account_id, f.id,
           row_number() over (partition by f.company_id, f.account_id
                              order by f.as_of desc, f.input_version desc, s.created_at desc,
                                       j.created_at desc, j.enqueue_sequence desc) as position
    from forecasts f join snapshots s on s.id = f.snapshot_id join jobs j on j.id = f.job_id
) ranked where position = 1;

insert into alerts (id, company_id, account_id, forecast_id, gap_id, event_key, code, title, message,
                    from_date, to_date, amount, probability, active, created_at)
select gen_random_uuid(), g.company_id, g.account_id, g.forecast_id, g.id, 'gap:' || g.id, g.kind,
       case when g.kind = 'CASH_GAP' then 'Возможная нехватка денег' else 'Остаток ниже резерва' end,
       'В сохранённом прогнозе есть период нарушения указанного порога. Откройте факторы и ограничения расчёта.',
       g.from_date, g.to_date, g.point_shortfall, g.probability, h.forecast_id = g.forecast_id, f.created_at
from forecast_gaps g join forecasts f on f.id = g.forecast_id
join forecast_alert_heads h on h.company_id = g.company_id and h.account_id = g.account_id;

insert into alerts (id, company_id, account_id, forecast_id, plan_id, event_key, code, title, message,
                    from_date, amount, active, created_at)
select gen_random_uuid(), f.company_id, f.account_id, f.id, (p.value ->> 'id')::uuid,
       'plan:' || (p.value ->> 'id'), 'OVERDUE_PLAN', 'Ожидаемый платёж не исполнен',
       'В снимке расчёта остался открытый план с прошедшей датой. Проверьте исполнение и срок; дата автоматически не переносилась.',
       (p.value ->> 'dueDate')::date, (p.value ->> 'remainingAmount')::numeric, h.forecast_id = f.id, f.created_at
from forecasts f join snapshots s on s.id = f.snapshot_id
join forecast_alert_heads h on h.company_id = f.company_id and h.account_id = f.account_id
cross join lateral jsonb_array_elements(coalesce(s.payload -> 'knownPlans', '[]'::jsonb)) p
where p.value ->> 'status' in ('OPEN', 'PARTIALLY_SETTLED')
  and (p.value ->> 'remainingAmount')::numeric > 0
  and (p.value ->> 'dueDate')::date <= f.as_of;

insert into alerts (id, company_id, account_id, forecast_id, event_key, code, title, message, active, created_at)
select gen_random_uuid(), f.company_id, f.account_id, f.id, 'quality', 'DATA_QUALITY', 'Ограничения прогноза',
       'У сохранённого расчёта есть ограничения данных или допущения. Неизвестная вероятность не означает отсутствие риска.',
       h.forecast_id = f.id, f.created_at
from forecasts f join forecast_alert_heads h on h.company_id = f.company_id and h.account_id = f.account_id
where f.quality <> 'GOOD'
   or f.response_json #>> '{riskEstimate,availability}' <> 'AVAILABLE'
   or exists (select 1 from jsonb_array_elements(coalesce(f.response_json -> 'warnings', '[]'::jsonb)) warning
              where warning.value ->> 'code' <> 'UNMATCHED_PLAN');
--rollback drop table alerts;
--rollback drop table forecast_alert_heads;
