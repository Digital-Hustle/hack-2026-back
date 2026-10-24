--liquibase formatted sql

--changeset orchestrator:003-imports
-- Staged rows never reach payments/forecast until an atomic finalize (handoff §2).
create table imports (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    status varchar(16) not null check (status in ('STAGED', 'FINALIZED', 'CANCELLED')),
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    input_fingerprint char(64) not null,
    schema_version varchar(16) not null,
    rules_version varchar(32) not null,
    preprocessor_status varchar(16) not null,
    rows_staged integer not null check (rows_staged >= 0),
    rows_inserted integer null check (rows_inserted >= 0),
    rows_already_present integer null check (rows_already_present >= 0),
    issue_count bigint not null check (issue_count >= 0),
    created_by uuid not null,
    created_at timestamptz not null,
    finalized_by uuid null,
    finalized_at timestamptz null,
    version bigint not null default 0,
    unique (company_id, idempotency_key),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create table staged_payments (
    import_id uuid not null references imports (id),
    row_no integer not null check (row_no > 0),
    external_operation_id varchar(128) not null,
    source_row integer null,
    operation_date date not null,
    amount numeric(19, 2) not null check (amount > 0),
    direction varchar(3) not null check (direction in ('IN', 'OUT')),
    counterparty_inn varchar(12) null,
    counterparty_name varchar(500) null,
    purpose text null,
    category_code varchar(64) not null,
    category_source varchar(16) not null check (category_source in ('RULE', 'OTHER')),
    rule_version varchar(32) null,
    balance_eod numeric(19, 2) null,
    primary key (import_id, row_no),
    unique (import_id, external_operation_id)
);

create table staged_balances (
    import_id uuid not null references imports (id),
    date date not null,
    balance numeric(19, 2) not null,
    primary key (import_id, date)
);

create table import_issues (
    import_id uuid not null references imports (id),
    seq integer not null,
    code varchar(64) not null,
    severity varchar(16) not null,
    source_line bigint null,
    field varchar(64) null,
    message varchar(500) not null,
    primary key (import_id, seq)
);
--rollback drop table import_issues; drop table staged_balances; drop table staged_payments; drop table imports;
