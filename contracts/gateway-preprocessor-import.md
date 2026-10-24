# Gateway → Preprocessor → JSON → Orchestrator import

**Этот документ описывает legacy `prepare` + отдельный клиентский импорт.** После merged PR #21 **также реализован** серверный синхронный путь `Preprocessor → Orchestrator` (`stage → finalize → recovery`), подробно в [preprocessor-orchestrator.md](preprocessor-orchestrator.md). **Но новый upload endpoint пока не маршрутизируется Gateway**, поэтому два описанных ниже запроса по-прежнему актуальны для доступного через Gateway сценария. Источники: [Preprocessor README](../services/preprocessor/README.md), [JSON fixture](../services/preprocessor/docs/JSON.md), [Orchestrator README](../services/orchestrator/README.md).

## Что изменилось в main

- Новый `POST /api/v1/preprocessor/companies/{companyId}/imports` принимает `file`, `accountId`, `validationMode`, `completeness`, `declaredFromDate`, `declaredToDate`, `Idempotency-Key`; сам вызывает Orchestrator. **Он отсутствует в Gateway application.yml**, поэтому его нельзя выдавать за работающий внешний маршрут.
- Существующий `POST /api/v1/preprocessor/statements/prepare` сохранён и **не** вызывает Orchestrator.
- В Gateway добавлена отдельная совместимость для **Orchestrator**: `/companies/{c}/imports?accountId={a}` переписывается в `/companies/{c}/accounts/{a}/imports` только при валидном UUID, без изменения других query-параметров.

## 1. Подготовить CSV

```http
POST /api/v1/preprocessor/statements/prepare HTTP/1.1
Host: localhost:5001
Cookie: __Host-cashflow-access=<access-jwt>; __Host-cashflow-csrf=<csrf-cookie>
Origin: https://localhost
X-CSRF-Token: <csrf-token>
Content-Type: multipart/form-data; boundary=<generated>

--<generated>
Content-Disposition: form-data; name="file"; filename="demo.csv"
Content-Type: text/csv

company_id;operation_id;operation_date;amount;direction;counterparty_inn;counterparty_name;payment_purpose;balance_eod
DEMO;DEMO-1;2026-09-30;990.00;Списание;0012345678;Тестовый банк;Комиссия за ведение счета;1000.00
--<generated>--
```

Gateway переписывает **путь** в `/internal/v1/statements/prepare` и сохраняет поток multipart. Необязательное поле `companies` — отдельный CSV; **не** целевой UUID компании. CSV — UTF-8, `;`, обязательная header-строка. `operation_id` — внешний строковый ID; `amount > 0` с ≤2 знаками; направления `Поступление/Списание` либо `IN/OUT`. Preprocessor не доверяет входу, возвращает 400/413/415/422 при ошибках файла; `200` возможен с `report.status=PARTIAL` или `INVALID`. Проверяйте статус **в теле**.

Полный синтетический формат ответа: [examples/prepared-response.json](examples/prepared-response.json) или [исходный пример](../services/preprocessor/docs/JSON.md). Корень: `schemaVersion`, `rulesVersion`, `inputFingerprint`, `preparedAt`, `companies[]`, `operations[]`, `dailyBalances[]`, `report{}`. `operations[].category={code,title,source,ruleId,rulesVersion,needsReview}`; **нет** поля регулярности или признака сохранения в БД.

Пример CSV для ручного HTTP-тестирования: [examples/statement.csv](examples/statement.csv). Значения синтетические, не данные организаторов.

## 2. Отправить неизменённый Prepared JSON в Orchestrator

```http
POST /api/v1/orchestrator/companies/22222222-2222-4222-8222-222222222222/accounts/33333333-3333-4333-8333-333333333333/imports
Cookie: __Host-cashflow-access=<access-jwt>; __Host-cashflow-csrf=<csrf-cookie>
Origin: https://localhost
X-CSRF-Token: <csrf-token>
Idempotency-Key: 55555555-5555-4555-8555-555555555555
Content-Type: application/json

<полный ответ Preprocessor без изменений>
```

Orchestrator **не доверяет** подготовленному JSON: заново проверяет компании/счёт и WRITE role, соответствие `operations[].companyId` настроенному `account.externalCompanyId`, строки сумм/категории/направления, даты и дубли. Этап `stage` записывает `imports`, `staged_payments`, `staged_balances`, `import_issues` без появления в финансовой истории. Одинаковые `Idempotency-Key + canonical body` возвращают тот же импорт; тот же ключ и другое тело → 409.

## 3. Finalize в одной транзакции

```http
POST /api/v1/orchestrator/companies/22222222-2222-4222-8222-222222222222/accounts/33333333-3333-4333-8333-333333333333/imports/<importId>/finalize
Cookie: __Host-cashflow-access=<access-jwt>; __Host-cashflow-csrf=<csrf-cookie>
Origin: https://localhost
X-CSRF-Token: <csrf-token>
If-Match: "<current-import-version>"
```

Версию импортa получить из реального ответа `POST/GET /imports/{id}` — **не** подставляйте произвольное значение. При финализации сохраняются платежи, исходная ревизия категории, EOD anchors и completeness; `dataVersion` компании изменяется, дневная проекция пересчитывается. Повторный finalize идемпотентен. `If-Match` отсутствует → 428; старая версия → 409. `company_id` в CSV не даёт права пользователю. Не импортировать одновременно `all_operations.csv` и его индивидуальные `statements` как разные платежи.

## Тесты совместимости

- Выход Preprocessor → вход импорта: `PREPROCESSOR_SAMPLE_JSON=<path>` включает `PreprocessorContractTest` Orchestrator. Без файла тест пропускается.
- Проверить `PREPARED/PARTIAL/INVALID`, неверный companyId, повтор POST, conflict idempotency, повтор finalize, несовпадение EOD, HISTORY_GAP, закрытую дату и VIEWER=403.
- Финансовые payload и реальные ZIP-данные в git/логи не добавлять.
