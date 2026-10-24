--liquibase formatted sql

--changeset orchestrator:002-payments
create table payments (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    import_id uuid null,
    external_operation_id varchar(128) null,
    source_row integer null check (source_row > 0),
    operation_date date not null,
    amount numeric(19, 2) not null check (amount > 0),
    direction varchar(3) not null check (direction in ('IN', 'OUT')),
    counterparty_key varchar(600) null,
    counterparty_inn varchar(12) null check (counterparty_inn ~ '^([0-9]{10}|[0-9]{12})$'),
    counterparty_name varchar(500) null,
    purpose text null check (length(purpose) <= 2000),
    category_code varchar(64) not null,
    category_source varchar(16) not null check (category_source in ('RULE', 'COUNTERPARTY', 'USER', 'OTHER')),
    rule_version varchar(32) null,
    balance_eod numeric(19, 2) null,
    version bigint not null default 0 check (version >= 0),
    known_at timestamptz not null,
    created_at timestamptz not null,
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index payments_account_date on payments (company_id, account_id, operation_date, id);
create unique index payments_external_id on payments (company_id, account_id, external_operation_id)
    where external_operation_id is not null;

-- Append-only: the initial categorisation is revision 0, every correction adds a row.
create table payment_revisions (
    id uuid primary key,
    payment_id uuid not null references payments (id),
    company_id uuid not null,
    category_code varchar(64) not null,
    category_source varchar(16) not null check (category_source in ('RULE', 'COUNTERPARTY', 'USER', 'OTHER')),
    rule_version varchar(32) null,
    known_at timestamptz not null,
    actor_id uuid null,
    version bigint not null check (version >= 0),
    unique (payment_id, version)
);

create index payment_revisions_payment on payment_revisions (company_id, payment_id, known_at);

-- Recomputable projection, never the source of historical truth (handoff §2).
create table daily_observations (
    company_id uuid not null,
    account_id uuid not null,
    date date not null,
    inflow numeric(19, 2) null check (inflow >= 0),
    outflow numeric(19, 2) null check (outflow >= 0),
    eod numeric(19, 2) null,
    status varchar(16) not null check (status in ('OBSERVED', 'ZERO_CONFIRMED', 'MISSING')),
    payment_count integer not null check (payment_count >= 0),
    balance_mismatch boolean not null,
    input_version bigint not null,
    primary key (company_id, account_id, date),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

-- Append-only: a later range with the same days overrides an earlier one at its known_at.
create table completeness_ranges (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    from_date date not null,
    to_date date not null,
    completeness varchar(24) not null check (completeness in ('CONFIRMED_COMPLETE', 'UNKNOWN')),
    reason varchar(500) not null check (length(btrim(reason)) > 0),
    known_at timestamptz not null,
    actor_id uuid null,
    check (from_date <= to_date),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index completeness_account on completeness_ranges (company_id, account_id, from_date, to_date);

-- Append-only: a manual anchor never edits the statement; the latest known anchor for a date wins.
create table balance_anchors (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    date date not null,
    balance numeric(19, 2) not null,
    source varchar(16) not null check (source in ('STATEMENT', 'MANUAL')),
    reason varchar(500) not null check (length(btrim(reason)) > 0),
    known_at timestamptz not null,
    actor_id uuid null,
    supersedes_id uuid null references balance_anchors (id),
    import_id uuid null,
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index balance_anchors_account on balance_anchors (company_id, account_id, date, known_at);
--rollback drop table balance_anchors; drop table completeness_ranges; drop table daily_observations; drop table payment_revisions; drop table payments;
