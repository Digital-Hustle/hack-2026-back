--liquibase formatted sql

--changeset orchestrator:005-recurring
create table recurring_patterns (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    stable_key varchar(800) not null,
    direction varchar(3) not null check (direction in ('IN', 'OUT')),
    category_code varchar(64) not null,
    counterparty_key varchar(600) null,
    status varchar(16) not null check (status in ('DETECTED', 'CONFIRMED', 'EXCLUDED')),
    status_source varchar(16) not null check (status_source in ('DETECTOR', 'USER')),
    frequency varchar(16) not null check (frequency in ('WEEKLY', 'MONTHLY')),
    weekday smallint null check (weekday between 1 and 7),
    day_of_month smallint null check (day_of_month between 1 and 31),
    business_day_rule varchar(16) not null check (business_day_rule in ('NONE', 'FOLLOWING')),
    amount numeric(19, 2) not null check (amount > 0),
    observations integer not null check (observations >= 0),
    first_seen date not null,
    last_seen date not null,
    detector_version varchar(64) not null,
    input_version bigint not null,
    known_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0 check (version >= 0),
    check (first_seen <= last_seen),
    check ((frequency = 'WEEKLY' and weekday is not null and day_of_month is null)
        or (frequency = 'MONTHLY' and weekday is null and day_of_month is not null)),
    unique (company_id, account_id, stable_key),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

-- Full state on every change, for "what was CONFIRMED at cutoff T" (handoff §2, §3).
create table recurring_revisions (
    pattern_id uuid not null references recurring_patterns (id),
    company_id uuid not null,
    known_at timestamptz not null,
    payload jsonb not null,
    actor_id uuid null,
    version bigint not null,
    primary key (pattern_id, version)
);

create index recurring_revisions_at on recurring_revisions (company_id, pattern_id, known_at);

create table account_settings (
    company_id uuid not null,
    account_id uuid not null,
    settings_json jsonb not null,
    version bigint not null default 0,
    updated_at timestamptz not null,
    primary key (company_id, account_id),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create table settings_revisions (
    company_id uuid not null,
    account_id uuid not null,
    known_at timestamptz not null,
    version bigint not null,
    payload jsonb not null,
    actor_id uuid null,
    primary key (company_id, account_id, version)
);
--rollback drop table settings_revisions; drop table account_settings; drop table recurring_revisions; drop table recurring_patterns;
