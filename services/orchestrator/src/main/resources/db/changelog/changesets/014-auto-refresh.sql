--liquibase formatted sql

--changeset orchestrator:014-auto-refresh
-- Manual and automatic forecasts share one queue; the origin is visible to the API and used for supersession.
alter table jobs add column origin varchar(16) not null default 'MANUAL'
    check (origin in ('MANUAL', 'AUTO'));

create index jobs_active_account on jobs (company_id, account_id, type, status)
    where status in ('QUEUED', 'RUNNING');

--rollback drop index if exists jobs_active_account;
--rollback alter table jobs drop column if exists origin;
