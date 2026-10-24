--liquibase formatted sql

--changeset orchestrator:011-public-contract
-- Public contract alignment: OOO/IP legal forms, import statuses/counters/file metadata.
alter table companies drop constraint if exists companies_legal_form_check;
update companies set legal_form = case legal_form
    when 'LLC' then 'OOO'
    when 'INDIVIDUAL' then 'IP'
    else legal_form
end where legal_form in ('LLC', 'INDIVIDUAL');
alter table companies add constraint companies_legal_form_check check (legal_form in ('OOO', 'IP', 'OTHER'));

alter table imports drop constraint if exists imports_status_check;
update imports set status = case status
    when 'STAGED' then 'STAGING'
    when 'FINALIZED' then 'COMMITTED'
    when 'CANCELLED' then 'ABORTED'
    else status
end where status in ('STAGED', 'FINALIZED', 'CANCELLED');
alter table imports add constraint imports_status_check
    check (status in ('STAGING', 'COMMITTED', 'REJECTED', 'ABORTED'));

alter table imports add column if not exists file_name varchar(200);
alter table imports add column if not exists file_sha256 char(64);
alter table imports add column if not exists total_rows integer not null default 0 check (total_rows >= 0);
alter table imports add column if not exists accepted_rows integer not null default 0 check (accepted_rows >= 0);
alter table imports add column if not exists rejected_rows integer not null default 0 check (rejected_rows >= 0);
alter table imports add column if not exists duplicate_rows integer not null default 0 check (duplicate_rows >= 0);
alter table imports add column if not exists warning_count integer not null default 0 check (warning_count >= 0);
alter table imports add column if not exists from_date date;
alter table imports add column if not exists to_date date;
alter table imports add column if not exists validation_mode varchar(16) not null default 'SKIP_INVALID';
alter table imports add column if not exists completeness varchar(24) not null default 'UNKNOWN';
alter table imports add column if not exists data_version bigint;

update imports set file_name = 'statement.csv' where file_name is null;
update imports set file_sha256 = input_fingerprint where file_sha256 is null;
update imports set total_rows = rows_staged + rejected_rows + duplicate_rows where total_rows = 0;
update imports set accepted_rows = case when status = 'COMMITTED' then coalesce(rows_inserted, 0)
    when status = 'STAGING' then rows_staged else 0 end where accepted_rows = 0;
update imports set duplicate_rows = duplicate_rows + coalesce(rows_already_present, 0);
update imports set from_date = declared_from_date, to_date = declared_to_date
where completeness = 'CONFIRMED_COMPLETE';
update imports i set from_date = dates.first_date, to_date = dates.last_date
from (select import_id, min(operation_date) as first_date, max(operation_date) as last_date
      from staged_payments group by import_id) dates
where dates.import_id = i.id and i.from_date is null;

alter table imports alter column file_name set not null;
alter table imports alter column file_sha256 set not null;

alter table imports add constraint imports_file_sha256_check
    check (file_sha256 ~ '^[0-9a-f]{64}$');

create index if not exists imports_account_created on imports (company_id, account_id, created_at desc);
create index if not exists payments_counterparty
    on payments (company_id, account_id, counterparty_key) where counterparty_key is not null;
--rollback alter table companies drop constraint if exists companies_legal_form_check;
--rollback alter table imports drop constraint if exists imports_file_sha256_check;
--rollback drop index if exists payments_counterparty;
--rollback drop index if exists imports_account_created;
--rollback alter table imports drop column if exists accepted_rows, drop column if exists from_date, drop column if exists to_date;
