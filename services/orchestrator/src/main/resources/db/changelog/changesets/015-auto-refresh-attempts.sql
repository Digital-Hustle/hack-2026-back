--liquibase formatted sql

--changeset orchestrator:015-auto-refresh-attempts
create table auto_refresh_attempts (
    company_id uuid not null,
    account_id uuid not null,
    input_version bigint not null,
    attempted_at timestamptz not null,
    retry_after timestamptz not null,
    primary key (company_id, account_id),
    foreign key (account_id, company_id) references accounts (id, company_id)
);

--rollback drop table auto_refresh_attempts;
