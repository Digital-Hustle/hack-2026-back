--liquibase formatted sql

--changeset orchestrator:012-manual-payments
-- A fact may come from an imported statement, a manual owner entry, or a future bank connector.
alter table payments add column source varchar(16) not null default 'IMPORT'
    check (source in ('IMPORT', 'MANUAL', 'BANK'));
alter table payments add column created_by uuid null;
alter table payments add column manual_note varchar(500) null;

-- A manual fact never carries an external or import identity.
alter table payments add constraint payments_manual_source_check
    check (source <> 'MANUAL' or (import_id is null and external_operation_id is null));

create index payments_manual_lookup on payments (company_id, account_id, operation_date, amount, direction)
    where source = 'MANUAL';
--rollback drop index if exists payments_manual_lookup;
--rollback alter table payments drop constraint if exists payments_manual_source_check;
--rollback alter table payments drop column if exists manual_note;
--rollback alter table payments drop column if exists created_by;
--rollback alter table payments drop column if exists source;
