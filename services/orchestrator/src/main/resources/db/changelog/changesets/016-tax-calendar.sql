--liquibase formatted sql

--changeset orchestrator:016-tax-calendar
-- Справочник сроков уплаты 2026. Сроки на выходные перенесены на следующий рабочий день.
-- Сумм нет: календарь не определяет применимость налога и размер обязательства компании.
create table tax_calendar (
    id uuid primary key,
    year integer not null check (year between 2000 and 2100),
    due_date date not null,
    title varchar(300) not null,
    tax_code varchar(64) not null,
    audience varchar(32) not null,
    source_url varchar(500) not null,
    unique (year, due_date, tax_code, audience, title)
);

create index tax_calendar_year_date on tax_calendar (year, due_date);

insert into tax_calendar (id, year, due_date, title, tax_code, audience, source_url) values
 ('a0000000-2026-4000-8000-000000000001', 2026, '2026-01-28', 'Страховые взносы за декабрь 2025', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000002', 2026, '2026-03-02', 'Страховые взносы за январь', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000003', 2026, '2026-03-30', 'Страховые взносы за февраль', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000004', 2026, '2026-04-28', 'Страховые взносы за март', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000005', 2026, '2026-05-28', 'Страховые взносы за апрель', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000006', 2026, '2026-06-29', 'Страховые взносы за май', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000007', 2026, '2026-07-28', 'Страховые взносы за июнь', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000008', 2026, '2026-08-28', 'Страховые взносы за июль', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000009', 2026, '2026-09-28', 'Страховые взносы за август', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000010', 2026, '2026-10-28', 'Страховые взносы за сентябрь', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000011', 2026, '2026-11-30', 'Страховые взносы за октябрь', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000012', 2026, '2026-12-28', 'Страховые взносы за ноябрь', 'INSURANCE', 'EMPLOYER', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000013', 2026, '2026-01-28', 'НДС за IV квартал 2025, 1/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000014', 2026, '2026-03-02', 'НДС за IV квартал 2025, 2/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000015', 2026, '2026-03-30', 'НДС за IV квартал 2025, 3/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000016', 2026, '2026-04-28', 'НДС за I квартал, 1/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000017', 2026, '2026-05-28', 'НДС за I квартал, 2/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000018', 2026, '2026-06-29', 'НДС за I квартал, 3/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000019', 2026, '2026-07-28', 'НДС за II квартал, 1/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000020', 2026, '2026-08-28', 'НДС за II квартал, 2/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000021', 2026, '2026-09-28', 'НДС за II квартал, 3/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000022', 2026, '2026-10-28', 'НДС за III квартал, 1/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000023', 2026, '2026-11-30', 'НДС за III квартал, 2/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000024', 2026, '2026-12-28', 'НДС за III квартал, 3/3', 'VAT', 'VAT_PAYER', 'https://www.nalog.gov.ru/rn77/taxation/taxes/nds/'),
 ('a0000000-2026-4000-8000-000000000025', 2026, '2026-03-30', 'УСН за 2025 год (организации)', 'USN_ANNUAL', 'LEGAL_ENTITY', 'https://www.nalog.gov.ru/rn77/taxation/taxes/usn/'),
 ('a0000000-2026-4000-8000-000000000026', 2026, '2026-04-28', 'УСН за 2025 год (ИП)', 'USN_ANNUAL', 'IP', 'https://www.nalog.gov.ru/rn77/taxation/taxes/usn/'),
 ('a0000000-2026-4000-8000-000000000027', 2026, '2026-04-28', 'Авансовый платёж по УСН за I квартал', 'USN_ADVANCE', 'USN', 'https://www.nalog.gov.ru/rn77/taxation/taxes/usn/'),
 ('a0000000-2026-4000-8000-000000000028', 2026, '2026-07-28', 'Авансовый платёж по УСН за полугодие', 'USN_ADVANCE', 'USN', 'https://www.nalog.gov.ru/rn77/taxation/taxes/usn/'),
 ('a0000000-2026-4000-8000-000000000029', 2026, '2026-10-28', 'Авансовый платёж по УСН за 9 месяцев', 'USN_ADVANCE', 'USN', 'https://www.nalog.gov.ru/rn77/taxation/taxes/usn/'),
 ('a0000000-2026-4000-8000-000000000030', 2026, '2026-07-01', 'Взносы ИП 1% с дохода свыше 300 000 ₽ за 2025', 'IP_INSURANCE', 'IP', 'https://www.nalog.gov.ru/rn77/taxation/insprem/'),
 ('a0000000-2026-4000-8000-000000000031', 2026, '2026-12-28', 'Фиксированные взносы ИП за 2026', 'IP_INSURANCE', 'IP', 'https://www.nalog.gov.ru/rn77/taxation/insprem/');
--rollback drop table tax_calendar;
