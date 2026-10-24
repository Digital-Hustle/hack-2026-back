--liquibase formatted sql

--changeset orchestrator:014-manual-payment-corrections
create table payment_voids (
    payment_id uuid primary key references payments(id),
    company_id uuid not null references companies(id),
    account_id uuid not null references accounts(id),
    voided_at timestamptz not null,
    actor_id uuid not null,
    reason varchar(500) not null,
    replacement_payment_id uuid references payments(id)
);
create table manual_duplicate_resolutions (
    manual_payment_id uuid not null references payments(id),
    imported_payment_id uuid not null references payments(id),
    company_id uuid not null references companies(id),
    decision varchar(16) not null check (decision in ('SAME', 'DIFFERENT')),
    resolved_at timestamptz not null,
    actor_id uuid not null,
    primary key (manual_payment_id, imported_payment_id)
);
--rollback drop table manual_duplicate_resolutions;
--rollback drop table payment_voids;
