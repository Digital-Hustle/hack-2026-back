--liquibase formatted sql

--changeset orchestrator:006-snapshots
-- Immutable audit input: never updated after creation (handoff §2). A trigger enforces it.
create table snapshots (
    id uuid primary key,
    company_id uuid not null,
    account_id uuid not null,
    kind varchar(24) not null check (kind in ('FORECAST', 'BACKTEST', 'DETECT_RECURRING')),
    as_of date not null,
    input_version bigint not null check (input_version >= 0),
    fingerprint char(64) not null check (fingerprint ~ '^[0-9a-f]{64}$'),
    payload jsonb not null,
    provenance_json jsonb not null,
    created_at timestamptz not null,
    foreign key (account_id, company_id) references accounts (id, company_id)
);

create index snapshots_account on snapshots (company_id, account_id, kind, created_at);

create function snapshots_immutable() returns trigger language plpgsql as
'begin raise exception ''snapshots are immutable''; end';

create trigger snapshots_no_update before update or delete on snapshots
    for each row execute function snapshots_immutable();
--rollback drop trigger snapshots_no_update on snapshots; drop function snapshots_immutable(); drop table snapshots;
