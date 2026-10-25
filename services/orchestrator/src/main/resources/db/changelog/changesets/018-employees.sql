--liquibase formatted sql

--changeset orchestrator:018-employees
alter table recurring_patterns add column employee_payment boolean not null default false;
-- Manual rules have no fabricated detection observations or dates.
alter table recurring_patterns alter column first_seen drop not null;
alter table recurring_patterns alter column last_seen drop not null;
alter table recurring_patterns add constraint recurring_observation_provenance check (
    (detector_version = 'MANUAL_V1' and observations = 0 and first_seen is null and last_seen is null)
    or (detector_version <> 'MANUAL_V1' and observations > 0 and first_seen is not null and last_seen is not null)
);
alter table recurring_patterns add constraint recurring_company_identity unique (company_id, id);

create table employees (
    id uuid primary key,
    company_id uuid not null references companies (id),
    full_name varchar(500) not null check (length(trim(full_name)) > 0),
    position varchar(200),
    note varchar(1000),
    archived boolean not null default false,
    recurring_pattern_id uuid,
    version bigint not null default 0 check (version >= 0),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (recurring_pattern_id),
    foreign key (company_id, recurring_pattern_id) references recurring_patterns (company_id, id)
);
create index employees_company_name on employees (company_id, full_name, id);
