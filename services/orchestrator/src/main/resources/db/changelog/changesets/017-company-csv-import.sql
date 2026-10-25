--liquibase formatted sql

--changeset orchestrator:017-company-csv-import
-- Source company IDs belong to an owner, not to the globally addressable company UUID.
alter table companies add column external_company_id varchar(128);
alter table companies add column industry varchar(200);
alter table companies add column first_operation_date date;
alter table companies add column last_operation_date date;
alter table companies add column operations_count integer;

alter table companies add constraint companies_external_company_id_check
    check (external_company_id is null or external_company_id ~ '^[A-Za-z0-9_.:-]{1,128}$');
alter table companies add constraint companies_operation_count_check
    check (operations_count is null or operations_count >= 0);
alter table companies add constraint companies_operation_dates_check
    check (first_operation_date is null or last_operation_date is null
           or first_operation_date <= last_operation_date);
create unique index companies_owner_external_id_unique
    on companies (owner_user_id, external_company_id)
    where external_company_id is not null;

--rollback drop index if exists companies_owner_external_id_unique;
--rollback alter table companies drop constraint if exists companies_operation_dates_check;
--rollback alter table companies drop constraint if exists companies_operation_count_check;
--rollback alter table companies drop constraint if exists companies_external_company_id_check;
--rollback alter table companies drop column if exists operations_count;
--rollback alter table companies drop column if exists last_operation_date;
--rollback alter table companies drop column if exists first_operation_date;
--rollback alter table companies drop column if exists industry;
--rollback alter table companies drop column if exists external_company_id;
