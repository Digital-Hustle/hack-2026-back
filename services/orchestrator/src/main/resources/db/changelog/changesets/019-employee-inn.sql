--liquibase formatted sql

--changeset orchestrator:019-employee-inn
-- Employee INN is a personal 12-digit INN; a legal-entity 10-digit INN is not accepted.
alter table employees add column inn varchar(12) null check (inn ~ '^[0-9]{12}$');
--rollback alter table employees drop column if exists inn;
