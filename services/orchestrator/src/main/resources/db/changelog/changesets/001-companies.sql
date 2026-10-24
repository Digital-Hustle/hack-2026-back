--liquibase formatted sql

--changeset orchestrator:001-companies
create table companies (
    id uuid primary key,
    name varchar(500) not null check (length(btrim(name)) > 0),
    legal_form varchar(16) null check (legal_form in ('LLC', 'INDIVIDUAL')),
    inn varchar(12) null check (inn ~ '^([0-9]{10}|[0-9]{12})$'),
    kpp varchar(9) null check (kpp ~ '^[0-9]{9}$'),
    timezone varchar(64) not null,
    owner_user_id uuid not null,
    archived boolean not null default false,
    data_version bigint not null default 0 check (data_version >= 0),
    version bigint not null default 0 check (version >= 0),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table memberships (
    company_id uuid not null references companies (id),
    user_id uuid not null,
    role varchar(16) not null check (role in ('OWNER', 'ACCOUNTANT', 'VIEWER')),
    version bigint not null default 0 check (version >= 0),
    joined_at timestamptz not null,
    primary key (company_id, user_id)
);

create unique index memberships_one_owner on memberships (company_id) where role = 'OWNER';
create index memberships_user on memberships (user_id);

create table accounts (
    id uuid primary key,
    company_id uuid not null references companies (id),
    name varchar(200) not null check (length(btrim(name)) > 0),
    bank_name varchar(200) null,
    account_number varchar(20) null check (account_number ~ '^[0-9]{20}$'),
    external_company_id varchar(128) null check (external_company_id ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    currency varchar(3) not null default 'RUB' check (currency = 'RUB'),
    archived boolean not null default false,
    version bigint not null default 0 check (version >= 0),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (id, company_id)
);

create index accounts_company on accounts (company_id);
create unique index accounts_external_id on accounts (company_id, external_company_id)
    where external_company_id is not null;
--rollback drop table accounts; drop table memberships; drop table companies;
