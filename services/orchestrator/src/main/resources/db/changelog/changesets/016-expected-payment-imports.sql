--liquibase formatted sql

--changeset orchestrator:016-expected-payment-imports
-- Replay store of the atomic external registry import: a retry with the same Idempotency-Key and body returns the
-- original counters and rows, while another body with the same key is a conflict (FR-13).
create table expected_payment_imports (
    id uuid primary key,
    company_id uuid not null,
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    created_count integer not null check (created_count >= 0),
    updated_count integer not null check (updated_count >= 0),
    unchanged_count integer not null check (unchanged_count >= 0),
    item_ids jsonb not null,
    created_by uuid not null,
    created_at timestamptz not null,
    unique (company_id, idempotency_key),
    foreign key (company_id) references companies (id)
);

create index expected_payment_imports_company on expected_payment_imports (company_id, created_at desc);
--rollback drop table expected_payment_imports;
