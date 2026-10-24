--liquibase formatted sql

--changeset orchestrator:009-backtests
create table backtests (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    job_id uuid not null unique references jobs(id),
    snapshot_id uuid not null references snapshots(id),
    input_version bigint not null,
    algorithm_version varchar(64) not null,
    scope varchar(24) not null check (scope in ('HISTORY_ONLY','WITH_KNOWN_PLANS')),
    window_weeks integer not null check (window_weeks in (8,10,12)),
    first_origin date not null,
    last_origin date not null,
    step_days integer not null check (step_days between 7 and 30),
    status varchar(16) not null check (status in ('COMPLETE','PARTIAL')),
    response_json jsonb not null,
    created_at timestamptz not null,
    foreign key(account_id, company_id) references accounts(id, company_id),
    check(first_origin <= last_origin)
);
create index backtests_account on backtests(company_id, account_id, created_at, id);
create table backtest_origins (
    backtest_id uuid not null references backtests(id),
    as_of date not null,
    status varchar(16) not null check(status in ('EVALUATED','SKIPPED')),
    reason varchar(128),
    mae_balance numeric(19,2),
    evaluated_days integer not null check(evaluated_days between 0 and 30),
    primary key(backtest_id, as_of)
);
--rollback drop table backtest_origins; drop table backtests;
