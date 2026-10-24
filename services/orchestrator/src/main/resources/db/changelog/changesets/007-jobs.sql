--liquibase formatted sql

--changeset orchestrator:007-jobs
create table jobs (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    type varchar(24) not null check (type in ('FORECAST', 'BACKTEST', 'DETECT_RECURRING')),
    status varchar(16) not null check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    snapshot_id uuid not null references snapshots (id),
    result_id uuid not null,
    progress smallint not null default 0 check (progress between 0 and 100),
    error_json jsonb null,
    lease_owner varchar(128) null,
    lease_until timestamptz null,
    attempts integer not null default 0 check (attempts >= 0),
    max_attempts integer not null default 3 check (max_attempts > 0),
    not_before timestamptz not null,
    created_by uuid not null,
    created_at timestamptz not null,
    started_at timestamptz null,
    finished_at timestamptz null,
    unique (result_id),
    unique (snapshot_id),
    check ((status = 'RUNNING') = (lease_owner is not null and lease_until is not null)),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index jobs_claimable on jobs (not_before, created_at) where status = 'QUEUED';
create index jobs_expired_lease on jobs (lease_until) where status = 'RUNNING';
create index jobs_account on jobs (company_id, account_id, type, created_at);

-- External commands: the same key and body return the same job, another body is 409 (handoff §5).
create table idempotency_records (
    company_id uuid not null,
    idempotency_key varchar(128) not null,
    operation varchar(64) not null,
    request_hash char(64) not null,
    resource_id uuid not null,
    created_by uuid not null,
    created_at timestamptz not null,
    primary key (company_id, operation, idempotency_key)
);

-- Who did what, without financial bodies (handoff §2).
create table audit_entries (
    id uuid primary key,
    company_id uuid not null,
    actor_id uuid null,
    action varchar(64) not null,
    resource_type varchar(32) not null,
    resource_id uuid not null,
    created_at timestamptz not null
);

create index audit_company on audit_entries (company_id, created_at);
--rollback drop table audit_entries; drop table idempotency_records; drop table jobs;
