# Frontend API guide

Документ описывает публичный API через Gateway. Фронт не обращается напрямую к Auth/Profile,
Preprocessor, Orchestrator или Forecast по их внутренним портам.

В примерах:

```text
{{gateway}}       https://localhost:5001
{{companyId}}     UUID компании
{{accountId}}     UUID RUB-счёта
{{userId}}        UUID пользователя берётся из ответа verify
```

> Актуализация: публичные контроллеры и явные маршруты Gateway в `hack-2026-back` с учётом PR #38.
> Это руководство по frontend-сценариям, не полный машинный OpenAPI-каталог. Функции из других открытых PR не объявляются доступными до слияния и настройки Gateway.

## Общие правила запросов

Все запросы идут на `{{gateway}}`. Для браузера нужно использовать `credentials: "include"`.
Токены не кладутся в `localStorage`: Gateway использует HttpOnly cookies.

Перед первым изменяющим запросом:

```http
GET {{gateway}}/api/v1/auth-profile/auth/csrf
```

Ответ:

```json
{
  "token": "csrf-token",
  "headerName": "X-CSRF-Token"
}
```

Браузер сохраняет cookies из `Set-Cookie`, а для каждого `POST`, `PUT`, `PATCH`, `DELETE`
отправляет заголовок `X-CSRF-Token` со значением `token` и `Origin` текущего фронта.
`Cookie` и `Origin` вручную из JavaScript задавать не нужно.

Изменяющие запросы через Gateway требуют:

```http
Origin: https://frontend.example
X-CSRF-Token: <token из /auth/csrf>
Content-Type: application/json
```

Для операций с повтором нужен `Idempotency-Key`. Используйте новый UUID на новую операцию и
сохраняйте его при retry. Повтор того же ключа должен иметь то же тело.

Для редактирования ресурсов нужен `If-Match` с текущей версией:

```http
If-Match: "0"
```

Ошибки имеют `application/problem+json`. Для ветвления используйте поле `code`, а не текст
`detail`. Основные статусы: `400` — неверный запрос, `401` — нет сессии, `403` — CSRF или роль,
`404` — ресурс/компания недоступны, `409` — конфликт версии или idempotency key, `422` — бизнесовая
валидация, `429` — лимит, `503` — зависимость недоступна.

## 1. Авторизация и профиль

### Получить CSRF

```bash
curl -i -c cookies.txt \
  "{{gateway}}/api/v1/auth-profile/auth/csrf"
```

### Запросить OTP

```bash
curl -i -b cookies.txt -c cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'Content-Type: application/json' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -d '{"email":"user@example.com"}' \
  "{{gateway}}/api/v1/auth-profile/auth/code"
```

Ответ `202`:

```json
{
  "challengeId": "11111111-1111-4111-8111-111111111111",
  "expiresAt": "2026-10-24T09:05:00Z",
  "resendAvailableAt": "2026-10-24T09:01:00Z",
  "codeLength": 6
}
```

### Подтвердить OTP

```bash
curl -i -b cookies.txt -c cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'Content-Type: application/json' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -d '{"challengeId":"{{challengeId}}","code":"012345"}' \
  "{{gateway}}/api/v1/auth-profile/auth/verify"
```

Успешный ответ `200` устанавливает access, refresh и CSRF cookies. JSON содержит `user.id`,
`sessionId`, сроки действия cookies и профиль.

### Сессия и профиль

```http
GET    /api/v1/auth-profile/auth/sessions
DELETE /api/v1/auth-profile/auth/sessions/{sessionId}
DELETE /api/v1/auth-profile/auth/sessions
GET    /api/v1/auth-profile/profile/me
PUT    /api/v1/auth-profile/profile/me
POST   /api/v1/auth-profile/auth/refresh
POST   /api/v1/auth-profile/auth/logout
```

Обновление профиля:

```json
{
  "displayName": "Иван Петров",
  "version": 0
}
```

`refresh` вызывается без тела и только с credentials. При `401` на безопасном `GET` допустим один
refresh и один повтор чтения. Изменяющий запрос автоматически повторять нельзя: неизвестно, дошёл
ли он до сервера.

## 2. Компания, участники и счёт

### Создать компанию

```http
POST /api/v1/orchestrator/companies
```

```json
{
  "name": "ООО Ромашка",
  "legalForm": "OOO",
  "inn": "7701234567",
  "kpp": "770101001",
  "timezone": "Europe/Moscow"
}
```

Другие маршруты компании:

```text
GET    /api/v1/orchestrator/companies
GET    /api/v1/orchestrator/companies/{companyId}
PUT    /api/v1/orchestrator/companies/{companyId}              If-Match
POST   /api/v1/orchestrator/companies/{companyId}/archive      If-Match + Idempotency-Key
GET    /api/v1/orchestrator/companies/{companyId}/members
POST   /api/v1/orchestrator/companies/{companyId}/members
PATCH  /api/v1/orchestrator/companies/{companyId}/members/{userId} If-Match
DELETE /api/v1/orchestrator/companies/{companyId}/members/{userId}
GET    /api/v1/orchestrator/companies/{companyId}/accounts
POST   /api/v1/orchestrator/companies/{companyId}/accounts
GET    /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}
PUT    /api/v1/orchestrator/companies/{companyId}/accounts/{accountId} If-Match
POST   /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/archive If-Match + Idempotency-Key
```

Создать RUB-счёт:

```json
{
  "name": "Основной счёт",
  "bankName": "Центр-инвест",
  "accountNumber": "40702810000000000001",
  "externalCompanyId": "C001"
}
```

Списки компаний и счетов возвращают `PageRs<T>` с объектом `page: {page, size, total, hasNext}`.
У запросов `GET /companies` и `GET /companies/{companyId}/accounts` параметры `page` (от 0)
и `size` (1..200, по умолчанию 50). `members` возвращает `{items: [...]}`.
Сохраняйте `companyId`, `accountId`, `version`, `dataVersion` и `permissions` из ответов.
Роль `VIEWER` может читать, но не может импортировать данные, менять категории, планы,
регулярности или запускать расчёты.

## 3. Импорт CSV и Excel: рекомендуемый flow для фронта

Для фронта рекомендуется один публичный запрос Preprocessor. Он сам выполняет подготовку CSV/XLS/XLSX,
staging в Orchestrator и finalize. Это основной пользовательский путь.

```http
POST /api/v1/preprocessor/companies/{companyId}/imports
Content-Type: multipart/form-data
Idempotency-Key: UUID
```

Multipart-поля:

```text
file                 выписка CSV, XLSX или XLS, обязательное поле
accountId            UUID счёта, обязательное поле
validationMode       ATOMIC или SKIP_INVALID
completeness         необязательно: UNKNOWN или CONFIRMED_COMPLETE (по умолчанию выписка считается полной)
declaredFromDate     необязательно, YYYY-MM-DD; по умолчанию дата первой операции файла
declaredToDate       необязательно, YYYY-MM-DD; по умолчанию дата последней операции файла
```

**Выписка считается полной по умолчанию.** Чекбокс «выписка полная за период» и ручной ввод дат
первой и последней операции больше не нужны: если `completeness` и даты не переданы, сервер сам
подтверждает период от первой до последней операции файла. День внутри периода без операций —
подтверждённый ноль (`ZERO_CONFIRMED`), а не пропуск. Передавайте поля только если нужно иначе:
`completeness=UNKNOWN` — не подтверждать полноту (дни останутся `MISSING`), свои
`declaredFromDate`/`declaredToDate` — подтвердить более широкий период (все операции файла должны
в него попадать). Если в файле есть отклонённые строки или статус не `PREPARED`, полнота
автоматически не подтверждается (останется `UNKNOWN`); явный `CONFIRMED_COMPLETE` в таком случае
вернёт `422 INCOMPLETE_STATEMENT`.

Пример:

```bash
curl -i -b cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Idempotency-Key: 4c51f8f8-6c69-4bd0-b2a9-6d7622f2d2d1' \
  -F 'file=@statement.xlsx' \
  -F 'accountId={{accountId}}' \
  -F 'validationMode=SKIP_INVALID' \
  -F 'completeness=CONFIRMED_COMPLETE' \
  -F 'declaredFromDate=2026-09-01' \
  -F 'declaredToDate=2026-10-24' \
  "{{gateway}}/api/v1/preprocessor/companies/{{companyId}}/imports"
```

Фронтенд передаёт Excel **в том же multipart-поле file**, без конвертации в CSV. Для выбора файла
используйте accept=".csv,.xlsx,.xls". На каждом непустом листе Excel должна быть первая строка
с теми же заголовками, что и в CSV. Все листы объединяются в один импорт. Даты Excel
нормализуются в YYYY-MM-DD, суммы обрабатываются как десятичные числа без неявного округления.
Формулы принимаются только при наличии сохранённого в книге результата — сервер не выполняет формулы.
Числовые значения с более чем 15 значащими цифрами отклоняются; крупные суммы, ИНН и внешние
идентификаторы рекомендуется хранить текстом. Лимиты публичного импорта: 20 MiB и 50 000
операций суммарно по всем листам. Для ошибок структуры и точности ожидайте 422, превышения
лимитов — 413. Формат успешного JSON-ответа одинаковый для CSV, XLS и XLSX.

`201` означает, что импорт завершён и данные сохранены. Основные поля ответа:

```json
{
  "id": "import-uuid",
  "companyId": "company-uuid",
  "accountId": "account-uuid",
  "status": "COMMITTED",
  "totalRows": 1000,
  "acceptedRows": 998,
  "rejectedRows": 2,
  "duplicateRows": 0,
  "warningCount": 3,
  "fromDate": "2026-09-01",
  "toDate": "2026-10-24",
  "dataVersion": 4
}
```

После `201` можно сразу запрашивать историю и запускать detection. При `PARTIAL` UI должен показать
warnings/issues пользователю. Для просмотра сохранённых импортов доступны:

```text
GET  /api/v1/orchestrator/companies/{companyId}/imports?accountId={accountId}
GET  /api/v1/orchestrator/companies/{companyId}/imports/{importId}
GET  /api/v1/orchestrator/companies/{companyId}/imports/{importId}/issues
```

### Низкоуровневый import flow

Если фронту нужно отдельно показать preview JSON, используйте два запроса:

```text
1. POST /api/v1/preprocessor/statements/prepare       multipart CSV/XLS/XLSX → PreparedStatementRs
2. POST /api/v1/orchestrator/companies/{c}/accounts/{a}/imports  JSON → STAGING
3. POST /api/v1/orchestrator/companies/{c}/accounts/{a}/imports/{id}/finalize
```

В обычном UI эти три шага лучше скрыть за публичным upload endpoint выше.
Прямой вызов `/internal/v1/statements/prepare` из браузера запрещён: Gateway
публикует только `/api/v1/preprocessor/statements/prepare`.

**Важно:** `Idempotency-Key`, `accountId` и `validationMode` обязательны для публичного
`POST /preprocessor/companies/{companyId}/imports`. `completeness`, `declaredFromDate` и
`declaredToDate` необязательны (см. выше). `declaredFromDate` позже `declaredToDate` — `422 INVALID_DATE_RANGE`.
При retry возможен ответ `200` вместо первоначального `201`.


## 4. История, категории и качество данных

### Прочитать историю

```text
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/payments
    ?fromDate=2026-09-01&toDate=2026-10-24&page=0&size=50

GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/daily-history
    ?from=2026-09-01&to=2026-10-24
```

Также есть company-wide alias:

```text
GET /api/v1/orchestrator/companies/{companyId}/payments?accountId={accountId}&page=0&size=50
GET /api/v1/orchestrator/companies/{companyId}/payments/{paymentId}
```

Поиск `q` в списке операций (`GET .../payments?q=...`, без учёта регистра, по подстроке) ищет по
названию контрагента, назначению платежа, ИНН контрагента, примечанию ручной операции (`note`) и
сумме (например `1234.50`). Аннулированные (void) операции в список не попадают.

Статусы дневной истории:

```text
OBSERVED        есть наблюдаемые операции/остаток
ZERO_CONFIRMED  день явно подтверждён как полный и пустой
MISSING         данных недостаточно; это не нулевой поток
```

### Категории

```http
GET /api/v1/orchestrator/companies/{companyId}/categories
```

Изменить одну категорию:

```http
PATCH /api/v1/orchestrator/companies/{companyId}/payments/{paymentId}/category
If-Match: "0"
```

```json
{
  "categoryCode": "SUPPLIERS"
}
```

Массовое изменение до 200 платежей:

```http
POST /api/v1/orchestrator/companies/{companyId}/payments/category-changes
Idempotency-Key: UUID
```

```json
{
  "categoryCode": "SUPPLIERS",
  "payments": [
    {"paymentId":"payment-uuid-1","expectedVersion":0},
    {"paymentId":"payment-uuid-2","expectedVersion":1}
  ]
}
```

Для точечного account-scoped маршрута используется `PUT .../accounts/{accountId}/payments/{id}/category`.

Добавить неизвестный/полный период и ручной остаток:

```http
POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/completeness
POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/balance-anchors
```

```json
{
  "fromDate": "2026-10-01",
  "toDate": "2026-10-24",
  "completeness": "CONFIRMED_COMPLETE",
  "reason": "Полная банковская выписка за период"
}
```

### Оценка качества истории (PR #38)

```http
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/data-quality
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/data-quality?from=2026-09-01&to=2026-09-30
```

Пример формы ответа:

```json
{
  "accountId": "account-uuid",
  "fromDate": "2026-09-01",
  "toDate": "2026-09-30",
  "quality": "LIMITED",
  "completeDayCount": 26,
  "missingDayCount": 4,
  "balanceMismatchCount": 1,
  "emptyPurposeCount": 3,
  "warnings": [
    {"code": "MISSING_DAYS", "message": "Есть дни без подтверждённых данных: 4", "date": null},
    {"code": "BALANCE_MISMATCH", "message": "Найдены расхождения остатков: 1", "date": null},
    {"code": "EMPTY_PURPOSE", "message": "Операции без назначения: 3", "date": null}
  ]
}
```

Даты в примере условные. Без параметров анализируются последние 182 **закрытых**
календарных дня по часовому поясу компании. `from` и `to` указываются только вместе;
диапазон включительный, не более 366 дней, `to` должен быть раньше сегодняшней даты
компании. Неверный период — `422 INVALID_RANGE`.

`GOOD` — представлены полные дни всех семи дней недели и нет пробелов;
`LIMITED` — представлены все дни недели, но есть пропуски;
`INSUFFICIENT` — представлены менее семи разных дней недели с полной историей.
Отсутствующий день проекции считается `MISSING`, а не нулевым платежным днём.
`warnings[].date` у агрегированных предупреждений равен `null`.
`emptyPurposeCount` не включает аннулированные факты.

### Журнал подтверждений полноты (PR #38)

```http
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/completeness-ranges?page=0&size=50
```

Ответ соответствует стандартному `PageRs<CompletenessRs>`:

```json
{
  "items": [
    {
      "id": "range-uuid",
      "accountId": "account-uuid",
      "fromDate": "2026-09-01",
      "toDate": "2026-09-30",
      "completeness": "CONFIRMED_COMPLETE",
      "reason": "Полная выписка",
      "createdAt": "2026-10-01T10:00:00Z"
    }
  ],
  "page": {"page": 0, "size": 50, "total": 1, "hasNext": false}
}
```

`page` начинается с 0, `size` по умолчанию 50, допустимо 1..200; иначе `422 INVALID_PAGE`.
Записи отсортированы по `knownAt DESC, id DESC` (поле ответа — `createdAt`).
Это append-only история утверждений; поздний `UNKNOWN` может перекрывать прежний
`CONFIRMED_COMPLETE`. Для **текущего эффективного статуса дня** используйте
`GET /daily-history`, а не последнюю запись журнала саму по себе.
На оба GET у пользователя должны быть права чтения компании и счёта; чужие данные дают `404`.

### Ручные фактические платежи и корректировки

```http
POST /api/v1/orchestrator/companies/{companyId}/payments
Idempotency-Key: UUID

GET  /api/v1/orchestrator/companies/{companyId}/payments/{paymentId}/duplicate-candidates
POST /api/v1/orchestrator/companies/{companyId}/payments/{paymentId}/duplicate-resolutions
If-Match: "<version>"

POST /api/v1/orchestrator/companies/{companyId}/payments/{paymentId}/void
If-Match: "<version>"
```

Создание факта (только закрытый день компании; `accountId` передаётся **в теле**, не в пути):

```json
{
  "accountId": "account-uuid",
  "operationDate": "2026-10-01",
  "amount": "1500.00",
  "direction": "OUT",
  "counterpartyInn": null,
  "counterpartyName": "Поставщик",
  "purpose": "Оплата услуги",
  "categoryCode": "SUPPLIERS",
  "note": "Добавлено вручную"
}
```

Аннулирование: `{"reason":"Ошибка ввода"}`. Для решения похожих операций
передайте `{"importedPaymentId":"payment-uuid","importedPaymentVersion":1,"decision":"SAME","reason":"Один платёж"}`;
`decision` может быть `SAME` или `DIFFERENT`. По умолчанию похожие
операции **не** считаются дубликатами без подтверждения. Изменения влияют
на `dataVersion`; права на запись и версии проверяются сервером.

## 5. Регулярные платежи: составной frontend flow

Сейчас detection асинхронный. Для UI это три HTTP-запроса:

```text
1. POST recurring-detection-jobs  → создать расчёт, получить jobId
2. GET  companies/{companyId}/jobs/{jobId} → polling до SUCCEEDED/FAILED
3. GET  recurring-patterns → получить кандидатов
```

Запуск:

```bash
curl -sS -b cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 0d1c4c90-ff3a-4da9-9be3-3ef31bf9bd8b' \
  -d '{"asOfDate":"2026-10-24"}' \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/accounts/{{accountId}}/recurring-detection-jobs"
```

Ответ `202`:

```json
{
  "id": "job-uuid",
  "accountId": "account-uuid",
  "type": "DETECT_RECURRING",
  "status": "QUEUED",
  "snapshotId": "snapshot-uuid",
  "resultId": null,
  "progress": 0,
  "attempts": 0
}
```

Polling:

```bash
curl -sS -b cookies.txt \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/jobs/{{jobId}}"
```

Опрос делайте раз в 500–1000 ms. Завершение:

```text
SUCCEEDED → перейти к GET recurring-patterns
FAILED    → показать error.code, повторять тот же job не нужно
QUEUED/RUNNING → продолжать polling
```

Получить кандидатов:

```http
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/recurring-patterns
```

Пример кандидата:

```json
{
  "id": "pattern-uuid",
  "accountId": "account-uuid",
  "direction": "OUT",
  "categoryCode": "SUPPLIERS",
  "counterpartyKey": "supplier-a",
  "status": "DETECTED",
  "statusSource": "DETECTOR",
  "schedule": {
    "frequency": "WEEKLY",
    "weekday": 5,
    "dayOfMonth": null,
    "businessDayRule": "NONE"
  },
  "amount": "100.00",
  "observations": 4,
  "firstSeen": "2026-10-03",
  "lastSeen": "2026-10-24",
  "version": 0
}
```

Подтвердить или исключить:

```bash
curl -sS -b cookies.txt \
  -X PUT \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "0"' \
  -d '{"status":"CONFIRMED"}' \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/accounts/{{accountId}}/recurring-patterns/{{patternId}}/status"
```

Допустимые пользовательские статусы: `CONFIRMED`, `EXCLUDED`. `DETECTED` устанавливает Forecast,
но пользователь не должен отправлять его в decision endpoint.

Чтобы подтвердить сразу всех кандидатов, есть `POST …/recurring-patterns/confirm-detected`
(без тела и `If-Match`), см. раздел 9.1. В прогноз попадают только `CONFIRMED`.

### Клиентский helper, объединяющий три запроса

```ts
async function detectRecurring(accountId: string, asOfDate: string) {
  const created = await api.post(
    `/api/v1/orchestrator/companies/${companyId}/accounts/${accountId}/recurring-detection-jobs`,
    { asOfDate },
    { idempotencyKey: crypto.randomUUID() },
  );

  let job = created;
  while (job.status === "QUEUED" || job.status === "RUNNING") {
    await new Promise(resolve => setTimeout(resolve, 700));
    job = await api.get(`/api/v1/orchestrator/companies/${companyId}/jobs/${job.id}`);
  }
  if (job.status !== "SUCCEEDED") {
    throw new ApiError(job.error?.code ?? "DETECTION_FAILED");
  }
  return api.get(
    `/api/v1/orchestrator/companies/${companyId}/accounts/${accountId}/recurring-patterns`,
  );
}
```

Это объединение делает frontend API client. Держать сам HTTP POST открытым до окончания detection
не нужно: job нужен для восстановления после перезапуска и повторного polling.

## 6. Будущие обязательства и планы

Получить планы:

```http
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/expected-payments
    ?from=2026-10-25&to=2026-11-30
```

Создать ручной план:

```http
POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/expected-payments
```

```json
{
  "title": "Налог",
  "direction": "OUT",
  "amount": "50000.00",
  "dueDate": "2026-11-25",
  "categoryCode": "TAXES",
  "counterpartyKey": null,
  "note": "УСН за квартал",
  "relation": "ADDITIONAL",
  "recurringPatternId": null,
  "baselineCounterpartyKey": null,
  "source": "MANUAL",
  "sourceDocument": null
}
```

Остальные операции с планом:

```text
GET  /expected-payments/{planId}
PUT  /expected-payments/{planId}                         If-Match
POST /expected-payments/{planId}/cancel                  If-Match
GET  /expected-payments/{planId}/allocations
POST /expected-payments/{planId}/allocations
POST /allocations/{allocationId}/reverse
```

Сопоставить фактический платёж с планом:

```json
{
  "paymentId": "payment-uuid",
  "amount": "25000.00"
}
```

Будущие поступления можно не вводить вручную, а забрать из Битрикс24: см. раздел 6.1.

План, связанный с регулярностью, не должен одновременно учитываться как дополнительный платёж.
Используйте `relation: "REPLACES_RECURRING"` и `recurringPatternId`, если план заменяет
подтверждённую регулярность.

### Налоговый календарь

```http
GET /api/v1/orchestrator/companies/{companyId}/tax-calendar?year=2026
```

```json
{
  "year": 2026,
  "items": [
    {
      "dueDate": "2026-04-28",
      "title": "УСН за 2025 год (ИП)",
      "taxCode": "USN_ANNUAL",
      "audience": "IP",
      "sourceUrl": "https://www.nalog.gov.ru/rn77/taxation/taxes/usn/"
    }
  ]
}
```

Это справочные сроки: сумм нет, и применимость налога к компании не проверяется. `audience`:
`EMPLOYER`, `VAT_PAYER`, `USN`, `LEGAL_ENTITY`, `IP`. Планы в прогноз календарь не добавляет.

Имитация интеграции с 1С: кнопка «Загрузить из 1С» вызывает `POST .../integrations/onec/sync`
(реальной 1С нет, загружается фиксированный реестр из трёх документов). Описание — в разделе 6.2.

## 6.1. Интеграция с Битрикс24: будущие поступления из CRM

Бэкенд читает из Битрикс24 открытые **сделки** и **счета** со сроком оплаты в ближайшие N дней
и превращает их в обычные ожидаемые платежи (раздел 6). Отдельного хранилища нет: синхронизированные
документы видны в `GET /expected-payments`, а в прогноз попадают при `scope: "WITH_KNOWN_PLANS"`.
Берутся только поступления (`direction: IN`), потому что расходов в CRM обычно нет.

Все пути ниже относительны `/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}`.

| Метод и путь | Роль | Назначение |
|---|---|---|
| `GET /integrations/bitrix24` | любой участник | статус: настроен ли вебхук на сервере, сколько активных планов из Битрикс24 |
| `POST /integrations/bitrix24/sync` | OWNER, ACCOUNTANT | синхронизация или предпросмотр (`dryRun`) |

`POST` требует те же `Origin` и `X-CSRF-Token`, что и любой изменяющий запрос. `Idempotency-Key` и
`If-Match` не нужны: повтор без изменений в CRM ничего не меняет (`dataChanged: false`).

### Откуда берётся вебхук

В Битрикс24: «Разработчикам» → «Другое» → «Входящий вебхук», права **CRM (crm)**. Портал выдаёт адрес
вида `https://<портал>.bitrix24.ru/rest/<id пользователя>/<код>/`.

Адрес содержит секрет, поэтому:

- сервер его **не сохраняет, не логирует и не возвращает** (в ответе только домен портала);
- фронт передаёт его в каждом запросе синхронизации и держит только в памяти страницы, не в
  `localStorage`. Поле ввода — `type="password"`;
- для демо вебхук можно задать на сервере переменной `ORCHESTRATOR_BITRIX_WEBHOOK_URL`, тогда
  `webhookUrl` в запросе не нужен (`GET` вернёт `webhookConfigured: true` и `configuredPortal`).

Принимаются только `https` и домены из `allowedHostSuffixes` (по умолчанию `.bitrix24.ru`,
`.bitrix24.com`, `.bitrix24.by`, `.bitrix24.kz`, `.bitrix24.ua`, `.bitrix24.de`, `.bitrix24.eu`).
Коробочный Битрикс24 на своём домене подключается добавлением домена в
`ORCHESTRATOR_BITRIX_ALLOWED_HOST_SUFFIXES`.

### Демо-режим

`"mode": "DEMO"` работает без Битрикс24: бэкенд берёт фиксированный набор из 10 документов с датами
относительно сегодняшнего дня. В наборе есть открытые сделки с вероятностями, выигранная и
проигранная сделки, сделка в USD и счета в стадиях «новый», «отправлен», «оплачен», «отклонён».
Портал в ответе — `"demo"`. Это удобно для защиты, если нет доступа к порталу или интернету.

### Статус

```http
GET /integrations/bitrix24
```

```json
{
  "webhookConfigured": false,
  "configuredPortal": null,
  "allowedHostSuffixes": [".bitrix24.ru", ".bitrix24.com"],
  "demoAvailable": true,
  "activePlans": 5,
  "activeAmount": "732000.00",
  "lastSyncedAt": "2026-10-25T01:58:32.117431Z"
}
```

`activePlans` и `activeAmount` — открытые и частично оплаченные планы из Битрикс24 по этому счёту
(остаток к оплате). `lastSyncedAt` — время последнего изменения такого плана, `null`, если
синхронизаций не было.

### Синхронизация

```bash
curl -sS -b cookies.txt -X POST \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Content-Type: application/json' \
  -d '{"webhookUrl":"https://acme.bitrix24.ru/rest/1/abc123xyz/","dryRun":true}' \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/accounts/{{accountId}}/integrations/bitrix24/sync"
```

Все поля необязательны, пустое тело допустимо:

| Поле | По умолчанию | Смысл |
|---|---|---|
| `mode` | `LIVE` | `LIVE` — настоящий портал, `DEMO` — встроенные демо-данные |
| `webhookUrl` | вебхук с сервера | адрес входящего вебхука (до 300 символов) |
| `horizonDays` | `30` | срок оплаты от `fromDate` до `fromDate` + N дней, 1…90 |
| `fromDate` | сегодня | `YYYY-MM-DD`, первый день окна. Если прогноз строится на `asOfDate` в прошлом, передавайте `fromDate = asOfDate + 1`, иначе документы окажутся вне 30-дневного окна прогноза и на него не повлияют |
| `includeDeals` | `true` | читать сделки (`crm.deal.list`) |
| `includeInvoices` | `true` | читать счета (смарт-счета, `crm.item.list`, entityTypeId 31) |
| `weightByProbability` | `false` | сумма сделки × вероятность стадии (ожидаемые, а не обещанные деньги) |
| `categoryCode` | `SALES` | категория создаваемых планов |
| `dryRun` | `false` | только предпросмотр, ничего не записывается |

Ответ `200`:

```json
{
  "mode": "LIVE",
  "portal": "acme.bitrix24.ru",
  "dryRun": false,
  "from": "2026-10-25",
  "to": "2026-11-24",
  "fetched": 10,
  "created": 5, "updated": 0, "reopened": 0, "cancelled": 0, "unchanged": 0, "skipped": 5,
  "dataChanged": true,
  "items": [
    {
      "kind": "DEAL",
      "externalId": "acme.bitrix24.ru/deal/101",
      "title": "Поставка оборудования",
      "documentAmount": "350000.00",
      "planAmount": "280000.00",
      "dueDate": "2026-10-30",
      "stage": "OPEN",
      "probability": 80,
      "action": "CREATED",
      "reason": null,
      "planId": "plan-uuid"
    },
    {
      "kind": "DEAL",
      "externalId": "acme.bitrix24.ru/deal/104",
      "title": "Пилотный проект",
      "documentAmount": "120000.00",
      "planAmount": null,
      "dueDate": "2026-11-02",
      "stage": "LOST",
      "probability": 0,
      "action": "SKIPPED",
      "reason": "LOST",
      "planId": null
    }
  ]
}
```

```ts
type BitrixAction = "CREATED" | "UPDATED" | "REOPENED" | "CANCELLED" | "UNCHANGED" | "SKIPPED";

interface BitrixSyncItem {
  kind: "DEAL" | "INVOICE";
  externalId: string;               // "<портал>/deal/<id>" или "<портал>/invoice/<id>"
  title: string | null;
  documentAmount: string | null;    // сумма в CRM
  planAmount: string | null;        // сумма плана; null, если план не создаётся или отменяется
  dueDate: string | null;
  stage: "OPEN" | "WON" | "LOST" | null;   // null у планов, документ которых пропал из CRM
  probability: number | null;       // только у сделок
  action: BitrixAction;
  reason: string | null;            // код причины для SKIPPED и CANCELLED
  planId: string | null;            // ссылка на GET /expected-payments/{planId}
}

interface BitrixSyncResult {
  mode: "LIVE" | "DEMO"; portal: string; dryRun: boolean;
  from: string; to: string; fetched: number;
  created: number; updated: number; reopened: number; cancelled: number; unchanged: number; skipped: number;
  dataChanged: boolean;
  items: BitrixSyncItem[];          // отсортированы по dueDate
}
```

### Что делает синхронизация

| `action` | Когда |
|---|---|
| `CREATED` | новый открытый документ в рублях со сроком в горизонте → новый план |
| `UPDATED` | у документа изменились сумма, срок, стадия, название или плательщик |
| `REOPENED` | план был отменён, а документ снова открыт |
| `CANCELLED` | документ выигран/оплачен, проигран/отклонён, сменил валюту или пропал из CRM |
| `UNCHANGED` | всё совпадает, план не тронут |
| `SKIPPED` | план не создаётся, причина в `reason` |

Коды `reason` и подписи для UI:

| `reason` | Текст для пользователя |
|---|---|
| `WON` | Сделка выиграна или счёт оплачен: деньги придут по банковской выписке |
| `LOST` | Сделка проиграна или счёт отклонён |
| `CURRENCY_NOT_RUB` | Валюта документа не рубли |
| `NO_AMOUNT` | В документе нет суммы |
| `NO_DUE_DATE` | В документе нет срока оплаты |
| `OUT_OF_HORIZON` | Срок оплаты вне выбранного периода |
| `NOT_IN_BITRIX` | Документ больше не найден в Битрикс24 за этот период |
| `OTHER_ACCOUNT` | Документ уже привязан к другому счёту компании |
| `AMOUNT_BELOW_SETTLED` | Новая сумма меньше уже распределённой оплаты, план не изменён |
| `EXTERNAL_ID_TOO_LONG` | Слишком длинный идентификатор документа |

Созданный план — обычный ожидаемый платёж (раздел 6):

```json
{
  "title": "Битрикс24 · сделка 101: Поставка оборудования",
  "direction": "IN",
  "amount": "350000.00",
  "dueDate": "2026-10-30",
  "categoryCode": "SALES",
  "counterpartyKey": "bitrix24:company:11",
  "note": "Синхронизировано из Битрикс24, стадия C1:EXECUTING, вероятность 80%, плательщик: ООО «Ромашка»",
  "relation": "ADDITIONAL",
  "source": "CONTRACT",
  "sourceDocument": {"externalSystem": "BITRIX24", "externalId": "acme.bitrix24.ru/deal/101",
                     "documentNumber": "101", "issuedDate": "2026-10-05"},
  "status": "OPEN"
}
```

Сделки получают `source: "CONTRACT"`, счета — `"INVOICE"`. Признак «из Битрикс24» —
`sourceDocument.externalSystem === "BITRIX24"`: по нему в списке планов можно показать значок.

Что важно учесть в UI:

- Планы из Битрикс24 принадлежат синхронизации. Ручные правки такого плана перезапишутся
  следующей синхронизацией, а вручную отменённый план вернётся (`REOPENED`), если документ в CRM
  всё ещё открыт. Менять такие документы нужно в Битрикс24. Для этих планов кнопки «Изменить»
  и «Отменить» лучше скрыть или сопроводить предупреждением.
- Отменяются только планы этого счёта со сроком внутри выбранного периода и только тех видов,
  которые синхронизировались. Синхронизация «только сделки» не трогает планы из счетов.
- `weightByProbability` меняет суммы. Синхронизация с другим значением обновит все сделки, поэтому
  выберите один вариант и храните его как настройку экрана.
- Сопоставление с фактом работает как для любых планов: когда оплата придёт в выписке,
  `POST /expected-payments/{planId}/allocations` (раздел 6).
- Если `dataChanged: true`, прогноз становится `STALE`. Предложите пересчёт со
  `scope: "WITH_KNOWN_PLANS"`, иначе планы в прогноз не попадут.
- **Даты синхронизации и прогноза должны совпадать.** По умолчанию окно синхронизации начинается
  сегодня, а прогноз учитывает только планы в 30 днях после своего `asOfDate`. Если история
  заканчивается в прошлом (демо-данные организаторов) и прогноз строится на её последний день,
  передавайте в синхронизацию `fromDate = asOfDate + 1` (демо-режим тоже сдвинется), иначе
  документы создадутся, но на прогноз не повлияют; в результате прогноза будет предупреждение
  `PLAN_OUTSIDE_HORIZON`.

### Ошибки

| HTTP | `code` | Что показать |
|---|---|---|
| 422 | `BITRIX_NOT_CONFIGURED` | Вставьте вебхук или включите демо-режим |
| 422 | `BITRIX_WEBHOOK_INVALID` | Адрес вебхука неверный или домен не разрешён (`detail` — подробности) |
| 422 | `BITRIX_AUTH_FAILED` | Битрикс24 отклонил вебхук: проверьте код и права crm |
| 422 | `BITRIX_TOO_MANY_DOCUMENTS` | Слишком много документов (больше 1000 одного вида), уменьшите период |
| 422 | `INVALID_HORIZON`, `INVALID_CATEGORY`, `NOTHING_TO_SYNC`, `VALIDATION_FAILED` | ошибка формы |
| 403 | `FORBIDDEN` | Синхронизация доступна владельцу и бухгалтеру |
| 409 | `VERSION_CONFLICT`, `CONCURRENT_SYNC` | План изменился во время синхронизации, повторите |
| 502 | `BITRIX_UNAVAILABLE`, `BITRIX_ERROR`, `BITRIX_BAD_RESPONSE` | Битрикс24 недоступен или вернул ошибку |
| 503 | `BITRIX_RATE_LIMITED` | Битрикс24 ограничил частоту запросов, повторите через минуту |
| 504 | `BITRIX_TIMEOUT` | Битрикс24 не ответил вовремя |

Синхронизация «всё или ничего»: при ошибке ни один план не меняется. Если какую-то сделку или
счёт прочитать не удалось, бэкенд не отменит план «пропавшего» документа, а вернёт ошибку целиком.

### Экран «Интеграции → Битрикс24»

```text
GET integrations/bitrix24                  → карточка: портал, активных планов, сумма, последняя синхронизация
поле вебхука (password) + переключатели    → сделки / счета / учитывать вероятность, период 7/14/30/60/90
«Предпросмотр»   → POST sync {dryRun: true} → таблица items: действие, документ, сумма CRM → сумма плана, срок, причина
«Синхронизировать» → POST sync             → итог «создано 5, обновлено 0, отменено 0, пропущено 5»
«Демо-данные»    → POST sync {mode: "DEMO"}
если dataChanged → баннер «Прогноз устарел» → POST forecast-jobs {scope: "WITH_KNOWN_PLANS"}
```

Для таблицы предпросмотра подойдут цвета по `action`: зелёный `CREATED`/`REOPENED`, синий
`UPDATED`, серый `UNCHANGED`/`SKIPPED`, красный `CANCELLED`.

## 6.2. Демо-источник 1С: формат данных и как они попадают в систему

В репозитории есть заглушка 1С — скрипт `tools/one-c-mock/one_c_mock.py` (Python 3, без зависимостей).
Она отдаёт фиксированный реестр неоплаченных счетов и рассчитанного налога так, как мог бы отдать
обработка на стороне 1С. Это **демонстрация формата**, а не подключение к реальной 1С.

Что важно знать фронтенду:

- **Для экрана есть эндпоинт-имитация** `POST .../integrations/onec/sync` (ниже). Он сам создаёт три
  плана из демо-реестра 1С, повторное нажатие безопасно. Скрипт-заглушка `tools/one-c-mock` остаётся
  только справкой о формате: Gateway его не проксирует, и браузер к нему не обращается.
- Данные из 1С попадают в систему как **обычные ожидаемые платежи** с заполненным `sourceDocument`
  (`externalSystem: "1C_DEMO"`). Вручную их можно создать и через `POST /expected-payments` (раздел 6).
- После этого план ведёт себя как любой другой: виден в `GET /expected-payments`, в календаре
  обязательных платежей (если `direction: OUT`), в план-факте, а в прогноз попадает при
  `scope: "WITH_KNOWN_PLANS"`.

### Кнопка «Загрузить из 1С»: `POST .../integrations/onec/sync`

```bash
curl -sS -b cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Content-Type: application/json' \
  -d '{"fromDate":"2026-02-01","dryRun":false}' \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/accounts/{{accountId}}/integrations/onec/sync"
```

Роли: `OWNER` и `ACCOUNTANT` (у `VIEWER` — `403`). Оба поля необязательны, пустое тело допустимо:

| Поле | По умолчанию | Смысл |
|---|---|---|
| `fromDate` | сегодня | `YYYY-MM-DD`, начало окна. Сроки документов считаются от него: налог +3 дня, счёт поставщика +11, счёт покупателю +16 |
| `dryRun` | `false` | только предпросмотр, ничего не записывается |

Ответ `200`:

```json
{
  "source": "1C_DEMO", "demo": true, "dryRun": false, "from": "2026-10-25",
  "created": 3, "updated": 0, "unchanged": 0, "skipped": 0, "dataChanged": true,
  "items": [
    {"externalId": "tax-usn-9m-2026", "documentNumber": null, "title": "Налог по УСН за 9 месяцев (рассчитан в 1С)",
     "direction": "OUT", "amount": "25000.00", "dueDate": "2026-10-28", "action": "CREATED", "reason": null, "planId": "uuid"}
  ]
}
```

`action`: `CREATED` (план создан), `UPDATED` (документ уже был, срок сдвинут под новый `fromDate`),
`UNCHANGED` (ничего не изменилось), `SKIPPED` (причина в `reason`: `CANCELLED` — план отменён вручную,
`ALREADY_PAID` — уже частично оплачен, `OTHER_ACCOUNT` — документ уже загружен в другой счёт компании).
`dryRun` возвращает те же поля без `planId` у новых документов.

Что важно учесть в UI:

- Если `dataChanged: true`, прогноз становится `STALE`: предложите пересчёт со `scope: "WITH_KNOWN_PLANS"`.
- **Дата окна и дата прогноза должны совпадать.** Прогноз учитывает планы только в 30 днях после своего
  `asOfDate`. Если история заканчивается в прошлом, передавайте `fromDate = asOfDate + 1`, иначе на
  прогноз планы не повлияют (в результате будет предупреждение `PLAN_OUTSIDE_HORIZON`, см. раздел 8).
  Повторный вызов с другим `fromDate` сдвигает уже созданные планы (`UPDATED`).
- Признак «из 1С»: `sourceDocument.externalSystem === "1C_DEMO"`; можно писать «демо».

### Запуск скрипта-заглушки (только справка о формате)

```bash
python3 tools/one-c-mock/one_c_mock.py --port 8099
curl "http://127.0.0.1:8099/api/1c/future-payments?companyId=<uuid>&accountId=<uuid>"
curl "http://127.0.0.1:8099/health"        # {"status":"UP"}
```

Ответ не зависит от `companyId` и `accountId`: они только возвращаются в теле. Данные статичные,
в них три документа с фиксированными датами (28.10, 5.11 и 10.11.2026). Со временем такие даты станут
просроченными (`overdue: true`), для повторного показа поправьте даты в скрипте.

```ts
interface OneCDocument {
  externalSystem: "1C_DEMO";
  externalId: string;                  // уникален в пределах системы: "invoice-1001"
  documentType: "INVOICE" | "TAX";
  documentNumber: string | null;       // "СФ-1001", у налога null
  title: string;
  direction: "IN" | "OUT";
  amount: string;                      // два знака, валюта не передаётся: считаем RUB
  issuedDate: string;                  // "2026-10-20"
  dueDate: string;
  categoryCode: string;                // "SALES" | "SUPPLIERS" | "TAXES"
  counterpartyName: string;
  counterpartyInn: string | null;      // у налога null
}

interface OneCResponse {
  source: "1C_DEMO";
  demo: true;                          // показывайте пометку «демо-данные»
  companyId: string | null;
  accountId: string | null;
  items: OneCDocument[];
}
```

Набор в заглушке:

| `externalId` | Документ | Направление | Сумма | Срок |
|---|---|---|---|---|
| `invoice-1001` | `INVOICE` СФ-1001, счёт покупателю ООО «Ромашка» | `IN` | 100 000.00 | 2026-11-10 |
| `bill-2045` | `INVOICE` СЧ-2045, счёт поставщика за оборудование | `OUT` | 70 000.00 | 2026-11-05 |
| `tax-usn-9m-2026` | `TAX`, налог по УСН за 9 месяцев (рассчитан в 1С) | `OUT` | 25 000.00 | 2026-10-28 |

### Соответствие полей 1С и ожидаемого платежа

| Поле 1С | Поле `POST /expected-payments` |
|---|---|
| `title` | `title` |
| `direction` | `direction` |
| `amount` | `amount` |
| `dueDate` | `dueDate` |
| `categoryCode` | `categoryCode` (`^[A-Z][A-Z0-9_]{1,63}$`) |
| `counterpartyInn` | `counterpartyKey` = `"inn:" + ИНН`, как для контрагентов из выписки; при `null` — `null` |
| `counterpartyName` | `note` (отдельного поля для имени у плана нет) |
| `documentType` | `source`: `INVOICE` или `TAX` |
| `externalSystem`, `externalId`, `documentNumber`, `issuedDate` | `sourceDocument` |
| — | `relation: "ADDITIONAL"`, остальные связи `null` |

Пример: счёт покупателю из заглушки.

```json
{
  "title": "Счёт покупателю ООО «Ромашка»",
  "direction": "IN",
  "amount": "100000.00",
  "dueDate": "2026-11-10",
  "categoryCode": "SALES",
  "counterpartyKey": "inn:7701234567",
  "note": "ООО «Ромашка»",
  "relation": "ADDITIONAL",
  "recurringPatternId": null,
  "baselineCounterpartyKey": null,
  "source": "INVOICE",
  "sourceDocument": {
    "externalSystem": "1C_DEMO",
    "externalId": "invoice-1001",
    "documentNumber": "СФ-1001",
    "issuedDate": "2026-10-20"
  }
}
```

Ответ `201` — обычный ожидаемый платёж с теми же полями, `status: "OPEN"`, `overdue: false`.
Изменяющий запрос идёт через Gateway с `Origin` и `X-CSRF-Token`, как в разделе «Общие правила запросов».

Правила `sourceDocument`:

- `externalSystem` (до 64 символов) и `externalId` (до 128) обязательны, если объект передан;
  `documentNumber` — до 64 символов, `issuedDate` необязателен.
- Пара `externalSystem` + `externalId` **уникальна в компании**. Повторная загрузка того же документа
  вернёт `409 EXTERNAL_DOCUMENT_EXISTS`. Это не ошибка данных: документ уже загружен, повторно
  создавать его не нужно.
- Обновление уже загруженного документа делается обычным `PUT /expected-payments/{planId}` с `If-Match`.
  Отдельной синхронизации с 1С, которая сама обновляла бы и отменяла планы, нет.

### Что видно во фронтенде

Признак «из 1С» — `sourceDocument.externalSystem` (в заглушке `1C_DEMO`, у реальной выгрузки будет своё
значение). По нему в списке планов и в календаре обязательных платежей можно:

- показать значок источника и номер документа (`sourceDocument.documentNumber`);
- написать «демо» для `1C_DEMO`;
- показать дату выставления (`sourceDocument.issuedDate`) рядом со сроком оплаты.

Остальные правила отображения те же, что для любых планов: `remainingAmount`, `overdue`, оплата
через `allocations`, пересчёт прогноза после изменения (план увеличивает `dataVersion`, и прогноз
становится `STALE`).

На тестовом стенде с этими тремя документами и `scope: "WITH_KNOWN_PLANS"` прогноз ставит плановые
суммы на свои даты: списание 25 000.00 — 28 октября, списание 70 000.00 — 5 ноября и поступление
100 000.00 — 10 ноября. С `HISTORY_ONLY` этих сумм в прогнозе нет.

Налоговый справочник (`/tax-calendar`, раздел 6) и план `TAX` из 1С — независимые сущности. Справочник
показывает сроки без сумм, план несёт сумму из 1С. Автоматической связи между ними нет, но в интерфейсе
их удобно показывать рядом по дате.

## 7. Настройки прогноза

```text
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/forecast-settings
PUT /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/forecast-settings If-Match
```

Пример допустимых настроек:

```json
{
  "windowWeeks": 12,
  "horizonDays": 30,
  "bufferAmount": "0.00",
  "scenarioCount": 1000,
  "minCalibrationOrigins": 12,
  "calibrationStepDays": 7,
  "algorithm": "WEEKDAY_MEAN_V1"
}
```

`algorithm`: `WEEKDAY_MEAN_V1` (по умолчанию) или `WEEKDAY_ROBUST_V2`. Подробности и сравнение
моделей — в разделе 9.1.

## 8. Прогноз: составной flow

Для прогноза frontend делает:

```text
1. POST /accounts/{accountId}/forecast-jobs
2. GET  /companies/{companyId}/jobs/{jobId} — polling
3. GET  /accounts/{accountId}/forecasts/{resultId}
   или GET /accounts/{accountId}/forecasts/latest
```

Запуск:

```bash
curl -sS -b cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 9e5f2fa0-12d3-4b54-9a40-f3a85a65ed6c' \
  -d '{"asOfDate":"2026-10-24","scope":"WITH_KNOWN_PLANS"}' \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/accounts/{{accountId}}/forecast-jobs"
```

`scope`:

```text
HISTORY_ONLY       прогноз только по истории и подтверждённым регулярностям
WITH_KNOWN_PLANS   дополнительно учитывает открытые/частично оплаченные планы
```

**Окно прогноза и плановые платежи.** Прогноз покрывает 30 дней после `asOfDate`
(`asOfDate+1 … asOfDate+30`). Плановый платёж учитывается в прогнозе, только если его `dueDate`
попадает в это окно. Платёж с другой датой в прогноз не попадает: если `dueDate` позже окна, в
`warnings` результата будет `PLAN_OUTSIDE_HORIZON` (в `message` — окно и ближайшая дата вне него,
в `date` — эта дата), если раньше или равна `asOfDate` — `UNMATCHED_PLAN`. Типичная ловушка: история
загружена до января, а планы созданы на сегодняшние даты — для `asOfDate` = конец января они «вне
окна». Для таких данных ставьте `asOfDate` = последний день истории и создавайте планы (и синхронизацию
Bitrix24, см. `fromDate`) на даты в 30 днях после него. UI стоит показывать предупреждение
`PLAN_OUTSIDE_HORIZON` рядом с прогнозом и подсказывать окно. Планы также учитываются только при
`scope: "WITH_KNOWN_PLANS"`.

Результат содержит 30 дневных точек, баланс, потоки, качество, gaps, risk estimate и `trace`
(шаги расчёта, см. раздел 9.1). При
недостаточной истории `riskEstimate.probability` может быть `null`; UI не должен показывать это
как нулевой риск.

Быстрый экран dashboard:

```http
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/dashboard
```

Dashboard возвращает текущий баланс, последний прогноз, совместимый backtest, active alerts,
upcoming payments, quality, running jobs и permissions.

В PR #34 добавлены `refresh` и `runningJobs[].origin`:

```json
{
  "refresh": {
    "needsRefresh": true,
    "autoRefreshEnabled": true,
    "dataVersion": 8,
    "latestForecastInputVersion": 7
  },
  "runningJobs": [{"id": "job-id", "type": "FORECAST", "status": "QUEUED", "origin": "AUTO", "progress": 0}]
}
```

`runningJobs` — краткие записи, детали доступны через существующий polling jobs. `JobRs.origin` тоже
всегда равен `MANUAL` или `AUTO`. При отсутствии прогноза `latestForecastInputVersion=-1`.
`needsRefresh` отражает отставание версии актуального прогноза, а `autoRefreshEnabled` — конфигурацию;
эти флаги не гарантируют, что новое задание уже создано или что истории достаточно для расчёта.

План-факт для графика:

```http
GET /api/v1/orchestrator/companies/{companyId}/plan-fact?accountId={accountId}&fromDate=2026-09-01&toDate=2026-09-30&groupBy=DAY
```

Ответ: `companyId`, `accountId`, `fromDate`, `toDate`, `groupBy`, `today`, `totals`, `periods`.
В `periods` есть `periodStart/periodEnd`, `plannedIn/Out`, `actualIn/Out`, `varianceIn/Out`,
`unplannedIn/Out`, `overduePlans[]`, `unplannedFacts[]`. В `totals` дополнительно есть
`settledIn/Out`, `remainingIn/Out`, `overdueRemainingIn/Out`. Деньги — строки с двумя знаками.
`variance=actual-planned`; положительное `varianceOut` означает перерасход.

`groupBy` по умолчанию `DAY`, для `WEEK` границы понедельник–воскресенье обрезаются по диапазону.
Пустые периоды содержат нули. Диапазон включительный, до 366 дней (`422` при превышении).
Права OWNER/ACCOUNTANT/VIEWER; чужая компания или счёт — `404`.
Аннулированные платежи не входят в факт; SAME оставляет в расчёте банковскую замену.
Просрочка возвращается по `dueDate < today` только внутри выбранного диапазона, не за всю историю.

Другие result endpoints:

```text
GET /accounts/{accountId}/forecasts?limit=50&offset=0
GET /accounts/{accountId}/forecasts/latest
GET /accounts/{accountId}/forecasts/{forecastId}
GET /accounts/{accountId}/backtests?limit=50&offset=0
GET /accounts/{accountId}/backtests/{backtestId}
GET /accounts/{accountId}/backtests/{backtestId}/origins
```

### Предупреждения, риски и объяснения

```http
GET  /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/alerts?activeOnly=true&limit=50&offset=0
POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/alerts/{alertId}/acknowledge
GET  /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/forecasts/{forecastId}/gaps/{gapId}
```

`GET /alerts` и детали разрыва требуют READ-доступа; acknowledge — WRITE-доступ,
защищённый CSRF. Список возвращается в `{items: [...]}`.
`activeOnly` по умолчанию `false`. `POST .../acknowledge` подтверждает
предупреждение, а не изменяет сам прогноз. Не придумывайте вероятность при
отсутствии достаточных данных для её оценки.

### Задания и автоматическое обновление прогноза

```http
GET  /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/jobs
GET  /api/v1/orchestrator/companies/{companyId}/jobs/{jobId}
POST /api/v1/orchestrator/companies/{companyId}/jobs/{jobId}/cancel
```

`GET .../accounts/{accountId}/jobs` возвращает `{items: [JobRs, ...]}`.
Поля `JobRs`: `id, accountId, type, status, snapshotId, resultId, progress,
attempts, error, origin, createdAt, startedAt, finishedAt`.
`origin` — `MANUAL` или `AUTO`. `resultId` доступен только для `SUCCEEDED`.
Отмена — защищённый CSRF POST; задача может уже завершиться к моменту отмены.

`GET /dashboard` содержит `refresh.needsRefresh`,
`refresh.autoRefreshEnabled`, `refresh.dataVersion` и
`refresh.latestForecastInputVersion`. При изменении входов `dataVersion`
новый прогноз может автоматически запускаться фоновым планировщиком
Orchestrator, если `autoRefreshEnabled=true` и хватает исходных данных.
Существующее задание не обязательно появляется моментально — UI показывает
`runningJobs` и использует polling. Для отсутствующего актуального результата
не подменяйте `STALE` старым прогнозом без индикатора. Если автоматический
пересчёт выключен или не создал задание, предложите явный `POST /forecast-jobs`.

### Агрегированная аналитика по категориям и контрагентам

```http
GET /api/v1/orchestrator/companies/{companyId}/analytics/categories?accountId={accountId}&fromDate=2026-09-01&toDate=2026-09-30&direction=OUT
GET /api/v1/orchestrator/companies/{companyId}/analytics/counterparties?accountId={accountId}&fromDate=2026-09-01&toDate=2026-09-30&direction=OUT
```

Обязательные query-параметры: `accountId`, `fromDate`, `toDate` (включительно); необязательный `direction=IN|OUT`.
Допустимый период — от 1 до 366 календарных дней; неверный диапазон — `422 INVALID_RANGE`.
Доступ к компании и счёту проверяется; OWNER, ACCOUNTANT и VIEWER могут читать, посторонним — `404`.
Если `direction` отсутствует, направления IN и OUT агрегируются отдельно.

Пример ответа `/analytics/categories`:

```json
{
  "companyId": "company-uuid",
  "accountId": "account-uuid",
  "fromDate": "2026-09-01",
  "toDate": "2026-09-30",
  "direction": "OUT",
  "totals": [{"direction": "OUT", "amount": "100.00", "paymentCount": 3}],
  "items": [
    {"direction": "OUT", "categoryCode": "RENT", "amount": "40.00", "paymentCount": 2, "sharePercent": "40.00"},
    {"direction": "OUT", "categoryCode": "TAXES", "amount": "60.00", "paymentCount": 1, "sharePercent": "60.00"}
  ]
}
```

Ответ `/analytics/counterparties` имеет ту же обёртку, но `items` содержат
`direction`, `counterpartyKey`, `counterpartyInn`, `counterpartyName`, `amount`, `paymentCount`,
`sharePercent`. Группировка по стабильному `counterpartyKey`; название и ИНН берутся из
последнего по дате платежа. Неопознанные контрагенты попадают в группу с `counterpartyKey: null`.

`amount` — сумма активных фактических операций, `paymentCount` — количество операций.
`sharePercent` — процент от оборота *того же направления*, с округлением до сотых;
сумма процентов может немного отличаться от 100 из-за округления.
Все денежные значения и проценты — десятичные строки. Аннулированные платежи не учитываются,
исправленные категории учитываются; планы и прогноз не входят в результат.
Результат отражает текущее состояние, а не исторический `asOfDate`-срез.
Отсутствие платежей не означает подтверждённой полноты истории.
Сортировка: направление, сумма по убыванию, затем ключ. Если фактов нет, `items: []`,
а `totals` содержит нули для выбранных направлений.

## 9. Backtest

```http
POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/backtest-jobs
Idempotency-Key: UUID
```

```json
{
  "asOfDate": "2026-10-01",
  "firstOrigin": "2026-09-01",
  "lastOrigin": "2026-09-01",
  "stepDays": 7,
  "scope": "HISTORY_ONLY",
  "windowWeeks": 12,
  "evaluateRisk": false
}
```

Дальше используется тот же polling job endpoint. После `SUCCEEDED` читайте backtest и его
`origins`. Backtest нужен для экрана качества алгоритма, но не должен блокировать обычный forecast flow.

## 9.1. Качество прогноза: метрики, матрицы ошибок, логи и экспорт

Раздел описывает всё, что фронту нужно показать для контрольного прогона: пошаговый лог расчёта,
график прогноза, метрики (MAE, MSE, RMSE, WAPE, Accuracy, Precision, Recall, F1, вероятность) и
матрицы ошибок. Методика и смысл метрик — в [`FORECAST_QUALITY_REPORT.md`](FORECAST_QUALITY_REPORT.md).

Все пути ниже относительны `/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}`.
Все поля добавлены в существующие ответы, ломающих изменений нет. Результаты, рассчитанные до
этого обновления, не содержат новых полей: `quality`, `trace` и `origins[].days` будут `null`/`[]`,
UI должен показывать «Пересчитайте, чтобы увидеть метрики» вместо ошибки.

### Эндпоинты

| Метод и путь | Ответ | Заметки |
|---|---|---|
| `GET /forecasts/{id}` | JSON | как раньше, плюс `trace` |
| `GET /forecasts/{id}/trace` | `text/plain`, файл | `?format=json` → JSON-массив шагов |
| `GET /forecasts/{id}/export` | `text/csv`, файл | дневной ряд прогноза |
| `GET /backtests/{id}` | JSON | плюс `quality`, `trace`, `origins[].days[]`, `gapMetrics.f1` |
| `GET /backtests/{id}/origins` | `{"items":[…]}` | отсечки, каждая с `days[]` |
| `GET /backtests/{id}/trace` | `text/plain`, файл | `?format=json` → JSON-массив шагов |
| `GET /backtests/{id}/export` | `text/csv`, файл | прогноз против факта по отсечкам и дням |
| `GET /backtests/{id}/report` | `text/markdown`, файл | готовый отчёт: таблицы метрик и матриц |
| `POST /recurring-patterns/confirm-detected` | `{"items":[RecurringPattern]}` | см. ниже |

Файловые ответы приходят с `Content-Disposition: attachment; filename="…"`. Гейтвей отдаёт этот
заголовок в CORS (`Access-Control-Expose-Headers`), поэтому имя файла можно прочитать из `fetch`.
Права: чтение — OWNER/ACCOUNTANT/VIEWER, чужая компания или счёт — `404`.

Списки `GET /forecasts` и `GET /backtests` тоже возвращают полный JSON, включая `trace` и `days`.
Для карточек списка берите из них только шапку, а детали загружайте отдельным запросом по `id`.

### Скачивание файла из браузера

Запрос идёт с cookie, поэтому прямая ссылка `<a href>` работает на том же домене, а в остальных
случаях нужен `fetch` и Blob:

```ts
async function downloadFile(path: string, fallbackName: string) {
  const res = await fetch(`${gateway}/api/v1/orchestrator/companies/${companyId}/accounts/${accountId}${path}`, {
    credentials: "include",
  });
  if (!res.ok) throw await res.json(); // application/problem+json, ветвитесь по `code`
  const match = /filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") ?? "");
  const url = URL.createObjectURL(await res.blob());
  const a = Object.assign(document.createElement("a"), { href: url, download: match?.[1] ?? fallbackName });
  a.click();
  URL.revokeObjectURL(url);
}

downloadFile(`/backtests/${id}/report`, "backtest-report.md");
downloadFile(`/forecasts/${id}/trace`, "forecast-trace.txt");
```

GET-запросам CSRF-токен не нужен.

### Подтвердить все найденные регулярности одним запросом

Только подтверждённые (`CONFIRMED`) регулярные платежи попадают в прогноз. Для контрольного
датасета, где никто не разбирает кандидатов вручную, есть массовое действие:

```bash
curl -sS -b cookies.txt -X POST \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  "{{gateway}}/api/v1/orchestrator/companies/{{companyId}}/accounts/{{accountId}}/recurring-patterns/confirm-detected"
```

Тело и `If-Match` не нужны, роль OWNER или ACCOUNTANT. Ответ — `{"items":[…]}` с теми кандидатами,
которые стали `CONFIRMED` (`statusSource: "USER"`, `version` увеличен). Уже решённые вручную
(`CONFIRMED`/`EXCLUDED`) не затрагиваются. Операция идемпотентна по смыслу: повторный вызов вернёт
пустой `items`. После неё прогноз становится `STALE`, нужно запустить новый forecast job.
Рекомендуемая кнопка: «Подтвердить все найденные».

### Выбор алгоритма

`PUT …/forecast-settings` с `"algorithm"`:

```text
WEEKDAY_MEAN_V1      по умолчанию, среднее по дню недели
WEEKDAY_ROBUST_V2    праздники РФ, ограничение выбросов, сглаживание шумных дней
```

Чтобы прогноз и бэктест были сопоставимы, запускайте их с одними и теми же настройками. Значение
хранится в `algorithm` и `algorithmVersion` ответа. Любое другое значение вернёт `422`.
Сравнение V1 и V2 уже внутри бэктеста: `quality.benchmarks` и `quality.selection`.

### Шаги расчёта (`trace`)

`trace` есть у прогноза и у бэктеста: массив шагов, готовый для отображения таймлайном.

```json
{
  "seq": 7,
  "stage": "DAY",
  "message": "B(2026-10-26) = B(prev) + in - out",
  "data": {
    "weekday": "MONDAY", "plannedIn": "0.00", "plannedOut": "0.00",
    "recurringIn": "0.00", "recurringOut": "12000.00", "residualIn": "41000.00", "residualOut": "36500.00",
    "inflow": "41000.00", "outflow": "48500.00", "balance": "110000.00"
  }
}
```

`seq` монотонно растёт с 1, `data` — плоский словарь строк (деньги — строки с двумя знаками).
Состав стадий не фиксирован, не ветвитесь по полному набору: неизвестную стадию показывайте как есть.

```text
Прогноз:  VALIDATE, TRAINING_WINDOW, RECURRING_EXCLUDED, PLANS, RESIDUAL_LEVEL, DAY (×30),
          POINT_RESULT, RISK, GAP
Бэктест:  EXPERIMENT, ORIGIN (на каждую отсечку), METRICS, CONFUSION_MATRIX, BENCHMARK, MODEL_SELECTION
```

Шагов DAY 30 штук, их стоит сворачивать по умолчанию. Для скачивания лога используйте
`/trace` (txt), для экранного таймлайна подойдёт `trace` из основного ответа, лишний запрос не нужен.

### Метрики бэктеста (`quality`)

Типы (TypeScript). Деньги — строки, доли — числа от 0 до 1 или `null`, если не определены.

```ts
type Money = string;

interface ConfusionMatrix {
  threshold: Money;                 // «разрыв», если остаток ниже порога (обычно "0.00")
  truePositive: number; falsePositive: number; falseNegative: number; trueNegative: number;
  total: number;
  accuracy: number | null;
  precision: number | null;         // null, если модель не предсказала ни одного разрыва
  recall: number | null;            // null, если фактических разрывов не было
  specificity: number | null;
  f1: number | null;
  balancedAccuracy: number | null;
  prevalence: number | null;        // доля фактических разрывов
  majorityBaselineAccuracy: number | null; // accuracy при ответе «всегда как большинство»
}

interface ModelScore {
  model: string;                    // "WEEKDAY_MEAN_V1@12w", "NAIVE_LAST_BALANCE", …
  description: string;
  evaluatedDays: number;
  maeBalance: Money; mseBalance: Money; rmseBalance: Money;
  wapeBalance: number | null;       // Σ|ошибка| / Σ|факт|
  skillScoreVsNaive: number | null; // 1 − MAE/MAE(naive); > 0 лучше наивной
  inflowMae: Money; outflowMae: Money;
  dailyMatrix: ConfusionMatrix | null;
}

interface ModelSelection {
  selectedModel: string;
  holdoutFrom: string;              // с этой даты отсечки считается «отложенная» треть
  selectionOrigins: number; holdoutOrigins: number;
  selectedHoldoutMae: Money; primaryHoldoutMae: Money;
  candidates: { model: string; selectionMae: Money; holdoutMae: Money }[];
}

interface QualityMetrics {
  primary: ModelScore;              // модель, выбранная в настройках
  benchmarks: ModelScore[];         // эталоны на тех же днях
  originMatrix: ConfusionMatrix | null;          // «будет ли разрыв в 30 днях» по отсечкам
  scenarioDailyMatrix: ConfusionMatrix | null;   // по вероятности сценариев pBelowZero ≥ scenarioThreshold
  bufferDailyMatrix: ConfusionMatrix | null;     // «ниже буфера», только если bufferAmount > 0
  scenarioThreshold: number;        // 0.3
  selection: ModelSelection | null; // null, если отсечек слишком мало для честного выбора
  notes: string[];                  // готовые пояснения, показывать под таблицами
}
```

Где что лежит в ответе `GET /backtests/{id}`:

```text
maeBalance, rmseBalance              основная ошибка (как раньше)
horizons[]                           MAE/RMSE по дню горизонта 1…30 (график «ошибка по горизонту»)
gapMetrics                           эпизоды разрыва: TP/FP/FN, precision, recall, f1, medianLeadDays,
                                     dateMaeDays, shortfallMae (TN не определён по построению)
riskMetrics                          brierScore, calibrationBins[], interval80Coverage
quality                              см. выше
origins[]                            по каждой отсечке: asOfDate, status, reason, maeBalance, days[]
warnings[]                           в т.ч. OVERLAPPING_ORIGINS: показать как подсказку
```

Что показать:

- **MAE, MSE, RMSE, WAPE** — из `quality.primary`. MSE в ₽², RMSE в ₽: подписывайте единицы.
- **Accuracy, Precision, Recall, F1** — из `quality.primary.dailyMatrix` (по дням). Второй
  вариант, по датам отсечки, — `quality.originMatrix`; подпишите, что считается.
- **Оценки точности** — `skillScoreVsNaive` и сравнение моделей в таблице `benchmarks`.
- **Вероятность** — `riskMetrics.brierScore`, `calibrationBins` (график калибровки: `meanProbability`
  по X, `observedRate` по Y), `interval80Coverage` (идеал ≈ 0.8).
- Отдельным блоком `notes` как «как читать эти цифры».

Правила отображения, чтобы цифры не вводили в заблуждение:

1. Рядом с Accuracy всегда показывайте `majorityBaselineAccuracy` и `prevalence`. Разрывов
   в истории обычно мало, и «никогда не предсказывать разрыв» уже даёт высокую accuracy.
2. `null` в `recall`, `f1`, `precision`, `skillScoreVsNaive` — это «не определено», а не 0 и не 100%.
   Показывайте «н/д» с подсказкой (например, «в периоде не было ни одного разрыва»).
3. Матрица 2×2 рисуется так: строки — факт (разрыв / нет разрыва), столбцы — прогноз; ячейки
   `truePositive`, `falseNegative` / `falsePositive`, `trueNegative`. Под матрицей — число
   наблюдений `total` и порог `threshold`.
4. Если `quality.selection` равен `null`, блок «выбор модели» скрывается. Если не `null`,
   показывайте `selectedHoldoutMae` против `primaryHoldoutMae`: это ошибка на отсечках, которые не
   участвовали в выборе.
5. `riskMetrics` и вероятностные матрицы могут отсутствовать: нужен `evaluateRisk: true` в запросе
   бэктеста и не менее 12 «зрелых» отсечек. Без этого блок скрывайте, а не рисуйте нули.
6. Дневные наблюдения соседних отсечек пересекаются (шаг 7, горизонт 30), поэтому это не
   независимая выборка. Покажите предупреждение `OVERLAPPING_ORIGINS`, если оно есть в `warnings`.

### Данные для графиков

**Прогноз** (`GET /forecasts/{id}`, `days[30]`): ось X — `date`, линия — `balance`, коридор —
`balanceP10…balanceP90`, медиана — `balanceP50`. Если риск не посчитан, `balanceP*`,
`pBelowZero`, `pBelowBuffer` равны `null`. Нулевая линия и `bufferAmount` — горизонтальные
ориентиры. Разрывы — `gaps[]` (`fromDate`, `toDate`, `expectedMinimumBalance`, `probability`),
их удобно закрасить полосами. Столбцы притока и оттока — `inflow` и `outflow`, а разложение по
источникам — `plannedIn/Out`, `recurringIn/Out`, `residualIn/Out`.

**Прогноз против факта** (`GET /backtests/{id}`, `origins[].days[]`): выберите отсечку и рисуйте
`forecastBalance` и `actualBalance` по `date`. Дополнительно есть `balanceP10/P90`,
`forecastInflow/Outflow`, `actualInflow/Outflow` и флаги `predictedBreach`/`actualBreach`.

```ts
interface DayComparison {
  date: string; horizonDay: number;        // 1…30
  forecastBalance: Money; actualBalance: Money;
  forecastInflow: Money; forecastOutflow: Money;
  actualInflow: Money; actualOutflow: Money;
  balanceP10: Money | null; balanceP90: Money | null;
  pBelowZero: number | null;
  predictedBreach: boolean; actualBreach: boolean;
}
```

Отсечки со `status: "SKIPPED"` (нехватка факта или истории) имеют пустой `days`
и код причины в `reason`; рабочие — `status: "EVALUATED"`: показывайте их серыми пунктами в списке отсечек.

График «ошибка по горизонту» строится из `horizons[]` (`horizonDay`, `maeBalance`, `rmseBalance`).
Модели удобно сравнивать столбцами MAE из `quality.primary` и `quality.benchmarks`.

### Асинхронность

Бэктест считается долго (чем больше отсечек, тем дольше, включая расчёт эталонов и риска). Схема
та же, что и для прогноза: `POST backtest-jobs` → polling `jobs/{jobId}` каждые 0.5–1 с
→ `GET backtests/{resultId}`. Блокируйте только экран качества, но не основной прогноз.

### Сценарий контрольного прогона в UI

```text
1. Импорт CSV (раздел 3)
2. recurring-detection-jobs → polling
3. «Подтвердить все найденные» → POST recurring-patterns/confirm-detected
4. (необязательно) PUT forecast-settings, algorithm
5. forecast-jobs → polling → график прогноза, кнопки «Лог расчёта» и «CSV»
6. backtest-jobs (evaluateRisk: true, stepDays: 7, windowWeeks: 12) → polling
7. Экран качества: таблицы метрик, матрицы ошибок, график «прогноз против факта»,
   кнопки «Отчёт (Markdown)», «Лог эксперимента», «CSV прогноз/факт»
```

Требования к истории: одна отсечка бэктеста — около 114 дней (12 недель обучения и 30 дней
факта); вероятность разрыва и её проверка требуют не менее 12 зрелых отсечек (около 191 дня
истории). При нехватке `riskEstimate.probability` равна `null`, а не 0.

## 10. Готовая последовательность экранов

### Первый запуск

```text
GET csrf
→ POST auth/code
→ POST auth/verify
→ GET profile/me
→ GET companies
→ если компаний нет: POST companies
→ GET/POST accounts
```

### Загрузка выписки и обнаружение регулярностей

```text
POST preprocessor/companies/{companyId}/imports  (CSV, accountId, Idempotency-Key, validationMode, completeness)
→ GET daily-history / payments при необходимости показать preview
→ POST recurring-detection-jobs
→ polling GET companies/{companyId}/jobs/{jobId}
→ GET recurring-patterns
→ пользователь CONFIRMED или EXCLUDED каждый кандидат
```

### Подключение Битрикс24

```text
GET integrations/bitrix24
→ пользователь вставляет вебхук (или «Демо-данные»)
→ POST integrations/bitrix24/sync {dryRun: true} → предпросмотр
→ POST integrations/bitrix24/sync → планы появились в expected-payments
→ если dataChanged: POST forecast-jobs {scope: "WITH_KNOWN_PLANS"}
```

### Главный экран прогноза

```text
GET dashboard
→ при необходимости GET forecast-settings
→ пользователь добавляет expected-payments
→ POST forecast-jobs
→ polling GET job
→ GET forecasts/{resultId} или dashboard повторно
```

### После изменения данных

После импорта, исправления категории, подтверждения регулярности, создания/изменения плана, синхронизации Битрикс24 или
распределения оплаты предыдущий forecast становится `STALE` по `dataVersion`.
Frontend показывает состояние `dashboard.refresh`, текущие `runningJobs` и ожидает
автоматического обновления при включённом AutoRefresh. Не создавайте сразу
параллельное ручное задание, если уже есть подходящий активный job. При отключённом
автообновлении или отсутствии задания предоставьте пользователю ручной запуск
`POST /forecast-jobs` с новым `Idempotency-Key`.

## AI-ассистент (OmniRoute)

```http
POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/assistant/chat
Content-Type: application/json
```

Обычные cookie/CSRF/Origin правила Gateway. Читать могут OWNER, ACCOUNTANT и VIEWER.
Компания, принадлежность счёта и переданные `forecastId`/`gapId` проверяются до обращения к провайдеру.
Ассистент не изменяет финансовые данные и не запускает новые расчёты.

```json
{
  "message": "Какие расходы по категориям за этот период?",
  "fromDate": "2026-09-01",
  "toDate": "2026-09-30",
  "history": []
}
```

`message` обязателен, до 2000 символов. `history` необязателен: до 10 сообщений
`{role: "user" | "assistant", content: "..."}`, каждое до 2000 символов. Сервер не хранит историю.
Она передаётся как недоверенные данные и не является источником финансовых фактов, включая
элементы с ролью `assistant`. Не передавайте секреты, реквизиты или сырые выписки в вопросе/истории.

Новые необязательные поля:

| Поле | Назначение |
| --- | --- |
| `fromDate`, `toDate` | Обе границы включительно для аналитики/качества истории; только закрытые дни по часовому поясу компании, максимум 366 дней |
| `forecastId` | Конкретный сохранённый прогноз этого счёта; без поля выбирается последний опубликованный |
| `gapId` | Конкретный эпизод риска выбранного прогноза; требует `forecastId` |

Период без полей может быть выбран из вопроса: последние 7/30 закрытых дней, текущий месяц
до последнего закрытого дня, предыдущий календарный месяц или две явно написанные ISO-даты.
По умолчанию — последние 30 закрытых дней; этот выбор всегда показывается в `warnings` и источнике.
Для другого/неоднозначного периода сервер возвращает `NEEDS_CLARIFICATION`; передайте обе границы.
Период истории не меняет горизонт сохранённого прогноза: у каждого источника свои даты и версия.

```json
{
  "answer": "По данным приложения:\n- Категория «Аренда помещения», списание за 2026-09-01 — 2026-09-30: 30000.00 RUB; операций 1. [s1]",
  "model": "configured-model-id",
  "asOfDate": "2026-10-24",
  "dataVersion": 7,
  "forecastAvailable": false,
  "forecastStale": false,
  "status": "ANSWERED",
  "sources": [{
    "id": "s1",
    "type": "CATEGORY_ANALYTICS",
    "href": "/api/v1/orchestrator/companies/{companyId}/analytics/categories?accountId={accountId}&fromDate=2026-09-01&toDate=2026-09-30",
    "asOfDate": "2026-10-24",
    "dataVersion": 7,
    "fromDate": "2026-09-01",
    "toDate": "2026-09-30",
    "forecastId": null,
    "snapshotId": null,
    "staleness": "CURRENT"
  }],
  "facts": [{
    "id": "f2",
    "sourceId": "s1",
    "text": "Категория «Аренда помещения», списание за 2026-09-01 — 2026-09-30: 30000.00 RUB; операций 1."
  }],
  "warnings": ["Период истории: 2026-09-01 — 2026-09-30."]
}
```

Frontend показывает `answer`, обязательные `warnings` и ссылки `sources`. `facts[].sourceId`
связывает конкретное утверждение с источником. ID `s1`/`f2` локальны для ответа, их нельзя
переиспользовать в следующем запросе. `href` — серверный относительный GET-путь, используйте
существующий API-клиент с сессией. Источники агрегатов ведут на общие эндпоинты
`GET /companies/{companyId}/analytics/categories|counterparties` с теми же счётом и периодом.
Ассистент и эти эндпоинты используют один `AnalyticsService`; источник возвращает полный отчёт,
даже если список групп в контексте модели сокращён.
`dataVersion` источника прогноза/разрыва/backtest — версия сохранённого расчёта; верхний
`dataVersion` — текущая версия компании. `asOfDate` верхнего уровня — последний закрытый день,
а не дата построения прогноза. `snapshotId`/`forecastId` возвращаются там, где применимы.

| `status` | Поведение |
| --- | --- |
| `ANSWERED` | Есть подтверждённые серверные факты и источники |
| `OUT_OF_SCOPE` | Посторонний вопрос или запрос внешнего совета/нового сценарного расчёта; фиксированный отказ |
| `INSUFFICIENT_DATA` | Нужных данных нет либо ответ модели не прошёл проверку; неподтверждённый текст не возвращается |
| `NEEDS_CLARIFICATION` | Нужно уточнить показатель или период |

Эти состояния возвращаются с HTTP 200; для отказа/уточнения `facts` и `sources` пустые.
Старые поля ответа сохранены, но `answer` теперь собирается backend из проверенных фактов.

**Источники подбираются под вопрос:**

- OVERVIEW: остаток, до 10 планов, до 8 предупреждений из dashboard.
- FORECAST: сохранённый прогноз, 30 дневных точек, доступная вероятность, режим `scope`, качество и коды предупреждений.
- GAP_DETAILS: существующий сервис объяснения разрыва из его immutable snapshot; до 8 эпизодов,
  до 20 факторов каждого, рекомендации и ограничения приложения. Для точного эпизода задайте ID.
- CATEGORIES/COUNTERPARTIES: отчёты общего `AnalyticsService` по всем активным операциям периода, без аннулированных
  платежей; актуальные категории. До 64 групп категорий и 20 групп контрагентов; сокращение явно маркируется.
- QUALITY: полнота истории за выбранный период и совместимый с выбранным прогнозом backtest
  (алгоритм, scope, окно). MAE/RMSE возвращаются в RUB, процент точности не придумывается.

**Контроль ответа:** два ограниченных обращения к OmniRoute вне транзакций БД. Сначала модель
выбирает только разрешённые темы и относительный период, затем — до 12 идентификаторов фактов
из серверного контекста (до 240 фактов и 32 ограничений). Произвольные инструменты, SQL, tenant ID,
суммы, даты, URL и свободный текст модели не принимаются. Все финансовые утверждения `answer`
формируются backend; неизвестные ID, лишние поля и неподтверждённые ответы дают безопасный отказ.
Фактическая релевантность выбора модели остаётся предметом проверки на реальном провайдере;
свободных пояснений вне серверных фактов в этом режиме нет.

Сырые операции, назначения платежей, ИНН, номера счетов и полные snapshots провайдеру не передаются.
Передаются только разрешённые агрегаты/факты, ограниченные названия планов/контрагентов и вопрос/история.
Неполная история не выдаётся за нулевые потоки; отсутствие вероятности, `HISTORY_ONLY`, устаревание,
частичный backtest и сокращение контекста явно показываются независимо от выбранных моделью фактов.

Ошибки: `401` нет сессии, `404` нет доступа/чужой счёт или расчёт, `422 AI_INVALID_MESSAGES`
некорректный диалог, `422 AI_INVALID_CONTEXT` неполные даты или gapId без forecastId,
`422 AI_INVALID_PERIOD` незакрытый/слишком длинный период; `503 AI_NOT_CONFIGURED` нет ключа;
`502/503/504` ошибки провайдера.

Настройки Orchestrator: `OMNIROUTE_BASE_URL` (по умолчанию `https://omniroute.bratancorp.online/v1`),
`OMNIROUTE_API_KEY`, `OMNIROUTE_MODEL`, `OMNIROUTE_CONNECT_TIMEOUT`, `OMNIROUTE_REQUEST_TIMEOUT`.
Таймаут применяется к каждому из двух вызовов; frontend учитывает их суммарную длительность.
Ключ хранится только на сервере. Default `auto` не проверен: перед деплоем выберите фактически
поддерживаемый model ID и проверьте соблюдение JSON-протокола в staging.

## Календарь обязательных платежей

```http
GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/payment-calendar
    ?fromDate=2026-11-01&toDate=2026-11-30
```

Чтение для `OWNER`, `ACCOUNTANT`, `VIEWER` через Gateway. Обязательные ISO-даты,
границы включены, максимум **366 дней**; неверный формат/отсутствие параметров → `400`,
обратный или слишком большой диапазон → `422 INVALID_RANGE`, нет доступа или счёт
принадлежит другой компании → `404`, нет аутентификации → `401`. Годы: `0001–9999`.

Это календарь **списаний** одного счёта в RUB. Источники:

- Все неотменённые `expected-payments` с `direction=OUT`: ручные, счета, договоры,
  налоги, включая планы с `sourceDocument` внешней системы (например, 1С). Включены оплаченные планы, чтобы UI мог
  показать их исполнение; `remainingAmount=0.00`.
- Экземпляры `CONFIRMED` регулярностей `OUT`, начиная с `max(fromDate, today)`.
  `DETECTED`, `EXCLUDED`, поступления и статистический прогноз расходов не включаются.
  Прошлые регулярности не превращаются автоматически в долги.
- Налоговый справочник `/tax-calendar` сам не создаёт обязательств с суммами.
  Применимый налог нужно добавить через существующий `POST /expected-payments` с `source=TAX`.

Сокращённый пример ответа (остальные дни имеют ту же структуру):

```json
{
  "companyId": "company-uuid",
  "accountId": "account-uuid",
  "currency": "RUB",
  "timezone": "Europe/Moscow",
  "today": "2026-11-01",
  "dataVersion": 12,
  "fromDate": "2026-11-01",
  "toDate": "2026-11-30",
  "totals": {
    "amount": "10000.00",
    "settledAmount": "3000.00",
    "remainingAmount": "7000.00",
    "overdueAmount": "0.00"
  },
  "days": [{
    "date": "2026-11-02",
    "totals": {"amount":"10000.00","settledAmount":"3000.00","remainingAmount":"7000.00","overdueAmount":"0.00"},
    "items": [{
      "id": "PLANNED:plan-uuid",
      "kind": "PLANNED",
      "expectedPaymentId": "plan-uuid",
      "recurringPatternId": null,
      "title": "Аренда",
      "dueDate": "2026-11-02",
      "nominalDate": "2026-11-02",
      "categoryCode": "RENT",
      "counterpartyKey": "supplier-key",
      "source": "CONTRACT",
      "sourceDocument": null,
      "status": "PARTIALLY_SETTLED",
      "amount": "10000.00",
      "settledAmount": "3000.00",
      "remainingAmount": "7000.00",
      "overdue": false,
      "version": 1
    }]
  }],
  "overdueBeforeRangeAmount": "0.00",
  "overdueBeforeRange": [],
  "warnings": []
}
```

`days` содержит **каждую** дату диапазона, включая пустые дни (`items=[]`, суммы `0.00`).
Планы остаются на своей `dueDate`. `totals` суммирует только `days`, а просроченные
планы **до начала диапазона** вынесены в `overdueBeforeRange`, без повторного учёта
в `totals`. Просрочка определяется как `dueDate < today && remainingAmount > 0`,
где `today` вычислен по часовому поясу компании. Просрочка внутри диапазона остаётся
в соответствующем дне и входит в `totals.overdueAmount`.

Деньги всегда строки с двумя знаками. Оплата плана подтверждается только существующими
`allocations`; отмена распределения сразу восстанавливает остаток. Для изменения,
отмены или распределения используйте `expectedPaymentId` и существующие API планов,
для optimistic locking — `version` и `If-Match`.

Регулярный элемент: `kind=RECURRING`, `id=RECURRING:{patternId}:{nominalDate}`,
`expectedPaymentId=null`, `recurringPatternId` задан, `source=CONFIRMED_RECURRING`,
`status=SCHEDULED`, `settledAmount=null` (исполнение неизвестно),
`amount=remainingAmount` — ожидаемая сумма графика, `overdue=false`.
Заголовок такого элемента — код категории. Чтобы учитывать его оплату, создайте
план с `relation=REPLACES_RECURRING`, ID регулярности и сроком экземпляра, затем
распределите фактическую оплату. Неотменённый план заменяет экземпляр на совпавшую
номинальную или перенесённую дату, включая уже оплаченный план; другие даты графика
сохраняются. Отмена плана возвращает экземпляр графика.

Правила графика соответствуют календарю v1: `WEEKLY` по ISO-дню недели, `MONTHLY`
с ограничением 29/30/31 последним днём месяца; `FOLLOWING` переносит субботу/воскресенье
на понедельник, `NONE` сохраняет дату. Переносы через границу месяца/диапазона учтены.
При наличии регулярных элементов `warnings` содержит `SIMPLIFIED_BUSINESS_CALENDAR`
(официальные праздники/переносы не учитываются) и `RECURRING_SETTLEMENT_UNKNOWN`.

Ответ — текущее согласованное состояние (`REPEATABLE READ`), а не исторический
snapshot и не расчёт прогноза. Запрос ничего не создаёт, не меняет `dataVersion` и
не запускает Forecast. Исторический период показывает сегодняшнее исполнение планов.

## Массовая загрузка операций организаторов

`POST /api/v1/orchestrator/companies/operations/imports` — multipart/form-data, поле `file`.
Файл `all_operations.csv`: UTF-8 с опциональным BOM, разделитель `;`, колонки
`company_id;operation_id;operation_date;amount;direction;counterparty_inn;counterparty_name;payment_purpose;balance_eod`.
Лимиты: 100 МБ, 300000 операций, 500 компаний. Строки одной компании должны идти непрерывно.
Сначала импортируйте `companies.csv` через `POST /api/v1/orchestrator/companies/imports`.
Клиент работает через Gateway с авторизацией cookie/CSRF.

Импорт атомарный: неизвестная компания, недоступная компания, неверные поля, конфликтующий
`operation_id` или несогласованный остаток возвращают ошибку и не сохраняют часть файла.
Компания разрешается только в каталоге, принадлежащем текущему пользователю; для компании
создаётся или повторно используется RUB-счёт с `externalCompanyId` из CSV.
Операции и остатки сохраняются в ledger с `categoryCode=OTHER` (без автоматической категоризации);
факты с неизменным `operation_id` пропускаются.

**Полнота истории.** Выгрузка организаторов считается полной: импорт сам подтверждает период
от первой до последней операции счёта (`CONFIRMED_COMPLETE`). День внутри периода без операций —
подтверждённый ноль (`ZERO_CONFIRMED` в `daily-history`), а не пропуск. Дни вне периода остаются
`MISSING`. Поэтому прогноз и оценка качества работают сразу после загрузки, без `POST .../completeness`.
Если выгрузка заканчивается в прошлом, передавайте в прогноз `asOfDate` = последний день данных
(не «вчера»), иначе остаток на дату не восстановится и придёт `INSUFFICIENT_HISTORY`.

**Порядок для демо.** 1) `POST /companies/imports` с `companies.csv`
(`company_id;company_name;company_inn;legal_form;industry;first_operation_date;last_operation_date;operations_count`,
до 2000 строк, 2 МБ); 2) `POST /companies/operations/imports` с `all_operations.csv`;
3) `GET /companies` — список всех организаций, дальше обычный сценарий одной компании:
счёт → операции → прогноз → оценка качества.

Успешный ответ (200):
```json
{"companies":1,"inserted":2,"existing":0,
 "items":[{"externalCompanyId":"C001","companyId":"UUID","accountId":"UUID","inserted":2,"existing":0}]}
```

## Сотрудники и зарплата в прогнозе

Базовый путь: `/api/v1/orchestrator/companies/{companyId}/employees`.
Полный контракт, примеры JSON, версии и ошибки: [EMPLOYEES_API.md](EMPLOYEES_API.md).
Все маршруты доступны через Gateway с обычной access-cookie/CSRF защитой.

| Действие | Метод и путь |
|---|---|
| Создать карточку | `POST /employees`, тело `{fullName, position?, note?}`, Idempotency-Key |
| Список с пагинацией | `GET /employees?includeArchived=false&page=0&size=50` |
| Прочитать / изменить | `GET /employees/{id}`, `PUT /employees/{id}` |
| Назначить / изменить зарплату | `PUT /employees/{id}/salary` |
| Привязать существующий регулярный OUT | `PUT /employees/{id}/recurring-payment` |
| Отключить регулярную выплату | `DELETE /employees/{id}/recurring-payment` |
| Архивировать сотрудника и отключить выплату | `POST /employees/{id}/archive` |

Изменения существующей карточки требуют `If-Match` из `Employee.version` (начиная с 1).
Ответ Employee содержит `recurringPayment: RecurringPatternRs | null`. Для изменения уже связанной
зарплаты передайте ещё `recurringPatternVersion` из вложенного платежа (его версия начинается с 0).
Salary принимает accountId, amount, schedule и counterpartyInn/counterpartyName. Привязка существующей
регулярности принимает accountId, recurringPatternId, patternVersion и явно подтверждает этот платёж.

Новая зарплата имеет `observations=0`, `firstSeen=lastSeen=null`, `detectorVersion=MANUAL_V1`:
не рисуйте выдуманную историю обнаружения. У связанных выплат есть `employeePayment=true`.
Зарплата сразу входит в прогноз; старые выплаты того же получателя/категории исключаются из baseline.
Изменение/отключение выплаты увеличивает dataVersion; обновите карточку и статус актуальности прогноза.
Архив отключает будущие регулярные списания, но не отменяет явно созданный план финального расчёта.
В этой версии — одна регулярная выплата на сотрудника, без расчёта налогов/табеля и будущей даты смены оклада.
