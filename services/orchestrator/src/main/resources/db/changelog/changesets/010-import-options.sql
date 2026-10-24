--liquibase formatted sql
--changeset orchestrator:010-import-options
alter table imports add column file_name varchar(200);
alter table imports add column file_sha256 char(64);
alter table imports add column validation_mode varchar(16) not null default 'SKIP_INVALID'
    check (validation_mode in ('ATOMIC', 'SKIP_INVALID'));
alter table imports add column completeness varchar(24) not null default 'UNKNOWN'
    check (completeness in ('UNKNOWN', 'CONFIRMED_COMPLETE'));
alter table imports add column declared_from_date date;
alter table imports add column declared_to_date date;
alter table imports add column total_rows integer not null default 0 check (total_rows >= 0);
alter table imports add column rejected_rows integer not null default 0 check (rejected_rows >= 0);
alter table imports add column duplicate_rows integer not null default 0 check (duplicate_rows >= 0);
alter table imports add column warning_count bigint not null default 0 check (warning_count >= 0);
alter table imports add column data_version bigint check (data_version >= 0);
--rollback alter table imports drop column data_version, drop column warning_count, drop column duplicate_rows, drop column rejected_rows, drop column total_rows, drop column declared_to_date, drop column declared_from_date, drop column completeness, drop column validation_mode, drop column file_sha256, drop column file_name;
