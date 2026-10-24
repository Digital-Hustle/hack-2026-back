--liquibase formatted sql

--changeset orchestrator:004-plans
create table expected_payments (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    title varchar(200) not null check (length(btrim(title)) > 0),
    direction varchar(3) not null check (direction in ('IN', 'OUT')),
    amount numeric(19, 2) not null check (amount > 0),
    due_date date not null,
    category_code varchar(64) not null,
    counterparty_key varchar(600) null,
    note varchar(1000) null,
    source varchar(16) not null check (source in ('MANUAL', 'INVOICE', 'TAX', 'CONTRACT')),
    external_system varchar(64) null,
    external_id varchar(128) null,
    document_number varchar(64) null,
    issued_date date null,
    relation varchar(24) not null check (relation in ('ADDITIONAL', 'REPLACES_RECURRING', 'REPLACES_BASELINE')),
    recurring_pattern_id uuid null,
    baseline_counterparty_key varchar(600) null,
    status varchar(24) not null check (status in ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'CANCELLED')),
    settled_amount numeric(19, 2) not null default 0,
    known_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0 check (version >= 0),
    check (settled_amount >= 0 and settled_amount <= amount),
    check ((relation = 'ADDITIONAL' and recurring_pattern_id is null and baseline_counterparty_key is null)
        or (relation = 'REPLACES_RECURRING' and recurring_pattern_id is not null and baseline_counterparty_key is null)
        or (relation = 'REPLACES_BASELINE' and recurring_pattern_id is null and baseline_counterparty_key is not null)),
    check ((external_system is null) = (external_id is null)),
    unique (id, company_id, account_id),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index expected_payments_account on expected_payments (company_id, account_id, due_date, id);
create unique index expected_payments_external on expected_payments (company_id, external_system, external_id)
    where external_system is not null;

-- Full state after every change, so historical replay never relies on updated_at of the current row (handoff §2).
create table expected_payment_revisions (
    expected_payment_id uuid not null references expected_payments (id),
    company_id uuid not null,
    valid_at timestamptz not null,
    version bigint not null,
    payload jsonb not null,
    actor_id uuid null,
    primary key (expected_payment_id, version)
);

create index expected_payment_revisions_at on expected_payment_revisions (company_id, expected_payment_id, valid_at);

create table allocations (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    expected_payment_id uuid not null,
    payment_id uuid not null references payments (id),
    amount numeric(19, 2) not null check (amount > 0),
    created_at timestamptz not null,
    actor_id uuid null,
    reversed_at timestamptz null,
    reversal_actor_id uuid null,
    check (reversed_at is null or reversed_at >= created_at),
    foreign key (expected_payment_id, company_id, account_id)
        references expected_payments (id, company_id, account_id),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create unique index allocations_one_active_link on allocations (expected_payment_id, payment_id)
    where reversed_at is null;
create index allocations_payment on allocations (company_id, payment_id);
--rollback drop table allocations; drop table expected_payment_revisions; drop table expected_payments;
