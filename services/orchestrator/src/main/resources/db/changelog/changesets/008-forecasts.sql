--liquibase formatted sql

--changeset orchestrator:008-forecasts
create table forecasts (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    job_id uuid not null unique references jobs (id),
    snapshot_id uuid not null references snapshots (id),
    as_of date not null,
    horizon integer not null check (horizon = 30),
    algorithm_version varchar(64) not null,
    input_version bigint not null,
    scope varchar(24) not null check (scope in ('HISTORY_ONLY', 'WITH_KNOWN_PLANS')),
    quality varchar(16) not null check (quality in ('GOOD', 'LIMITED', 'INSUFFICIENT')),
    start_balance numeric(19, 2) not null,
    start_balance_date date not null,
    expected_minimum_balance numeric(19, 2) not null,
    first_expected_gap_date date null,
    max_expected_shortfall numeric(19, 2) not null check (max_expected_shortfall >= 0),
    settings_json jsonb not null,
    risk_json jsonb not null,
    summary_json jsonb not null,
    response_json jsonb not null,
    created_at timestamptz not null,
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index forecasts_account on forecasts (company_id, account_id, created_at);

create table forecast_days (
    forecast_id uuid not null references forecasts (id),
    date date not null,
    planned_in numeric(19, 2) not null,
    planned_out numeric(19, 2) not null,
    recurring_in numeric(19, 2) not null,
    recurring_out numeric(19, 2) not null,
    residual_in numeric(19, 2) not null,
    residual_out numeric(19, 2) not null,
    inflow numeric(19, 2) not null,
    outflow numeric(19, 2) not null,
    balance numeric(19, 2) not null,
    balance_p10 numeric(19, 2) null,
    balance_p50 numeric(19, 2) null,
    balance_p90 numeric(19, 2) null,
    p_below_zero numeric(6, 5) null check (p_below_zero between 0 and 1),
    p_below_buffer numeric(6, 5) null check (p_below_buffer between 0 and 1),
    primary key (forecast_id, date)
);

create table forecast_gaps (
    id uuid primary key,
    forecast_id uuid not null references forecasts (id),
    company_id uuid not null,
    account_id uuid not null,
    kind varchar(16) not null check (kind in ('CASH_GAP', 'BUFFER_BREACH')),
    from_date date not null,
    to_date date not null,
    threshold numeric(19, 2) not null,
    point_min numeric(19, 2) not null,
    point_shortfall numeric(19, 2) not null check (point_shortfall >= 0),
    probability numeric(6, 5) null check (probability between 0 and 1),
    shortfall_p50 numeric(19, 2) null,
    shortfall_p90 numeric(19, 2) null,
    shortfall_basis varchar(32) null,
    risk_availability varchar(32) not null,
    check (from_date <= to_date)
);
--rollback drop table forecast_gaps; drop table forecast_days; drop table forecasts;
