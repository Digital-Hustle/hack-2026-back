--liquibase formatted sql
--changeset orchestrator:010-import-options
alter table imports add column if not exists file_name varchar(200);
alter table imports add column if not exists file_sha256 char(64);
alter table imports add column if not exists validation_mode varchar(16) not null default 'SKIP_INVALID'
    check (validation_mode in ('ATOMIC', 'SKIP_INVALID'));
alter table imports add column if not exists completeness varchar(24) not null default 'UNKNOWN'
    check (completeness in ('UNKNOWN', 'CONFIRMED_COMPLETE'));
alter table imports add column if not exists declared_from_date date;
alter table imports add column if not exists declared_to_date date;
alter table imports add column if not exists total_rows integer not null default 0 check (total_rows >= 0);
alter table imports add column if not exists rejected_rows integer not null default 0 check (rejected_rows >= 0);
alter table imports add column if not exists duplicate_rows integer not null default 0 check (duplicate_rows >= 0);
alter table imports add column if not exists warning_count bigint not null default 0 check (warning_count >= 0);
alter table imports add column if not exists data_version bigint check (data_version >= 0);
--rollback alter table imports drop column data_version, drop column warning_count, drop column duplicate_rows, drop column rejected_rows, drop column total_rows, drop column declared_to_date, drop column declared_from_date, drop column completeness, drop column validation_mode, drop column file_sha256, drop column file_name;
