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
> Это руководство по frontend-сценариям, не полный машинный OpenAPI-каталог. Функции из других открытых PR (например, налоговый календарь PR #39) не объявляются доступными до слияния и настройки Gateway.

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

## 3. Импорт CSV: рекомендуемый flow для фронта

Для фронта рекомендуется один публичный запрос Preprocessor. Он сам выполняет подготовку CSV,
staging в Orchestrator и finalize. Это основной пользовательский путь.

```http
POST /api/v1/preprocessor/companies/{companyId}/imports
Content-Type: multipart/form-data
Idempotency-Key: UUID
```

Multipart-поля:

```text
file                 CSV выписка, обязательное поле
accountId            UUID счёта, обязательное поле
validationMode       ATOMIC или SKIP_INVALID
completeness         UNKNOWN или CONFIRMED_COMPLETE
declaredFromDate     YYYY-MM-DD, обязательно для CONFIRMED_COMPLETE
declaredToDate       YYYY-MM-DD, обязательно для CONFIRMED_COMPLETE
```

Пример:

```bash
curl -i -b cookies.txt \
  -H 'Origin: https://frontend.example' \
  -H 'X-CSRF-Token: {{csrfToken}}' \
  -H 'Idempotency-Key: 4c51f8f8-6c69-4bd0-b2a9-6d7622f2d2d1' \
  -F 'file=@statement.csv' \
  -F 'accountId={{accountId}}' \
  -F 'validationMode=SKIP_INVALID' \
  -F 'completeness=CONFIRMED_COMPLETE' \
  -F 'declaredFromDate=2026-09-01' \
  -F 'declaredToDate=2026-10-24' \
  "{{gateway}}/api/v1/preprocessor/companies/{{companyId}}/imports"
```

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
1. POST /api/v1/preprocessor/statements/prepare       multipart CSV → PreparedStatementRs
2. POST /api/v1/orchestrator/companies/{c}/accounts/{a}/imports  JSON → STAGING
3. POST /api/v1/orchestrator/companies/{c}/accounts/{a}/imports/{id}/finalize
```

В обычном UI эти три шага лучше скрыть за публичным upload endpoint выше.
Прямой вызов `/internal/v1/statements/prepare` из браузера запрещён: Gateway
публикует только `/api/v1/preprocessor/statements/prepare`.

**Важно:** `Idempotency-Key`, `accountId`, `validationMode` и `completeness` обязательны
для публичного `POST /preprocessor/companies/{companyId}/imports`.
При `CONFIRMED_COMPLETE` также обязательны `declaredFromDate` и `declaredToDate`.
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

План, связанный с регулярностью, не должен одновременно учитываться как дополнительный платёж.
Используйте `relation: "REPLACES_RECURRING"` и `recurringPatternId`, если план заменяет
подтверждённую регулярность.

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

Результат содержит 30 дневных точек, баланс, потоки, качество, gaps и risk estimate. При
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

После импорта, исправления категории, подтверждения регулярности, создания/изменения плана или
распределения оплаты предыдущий forecast становится `STALE` по `dataVersion`.
Frontend показывает состояние `dashboard.refresh`, текущие `runningJobs` и ожидает
автоматического обновления при включённом AutoRefresh. Не создавайте сразу
параллельное ручное задание, если уже есть подходящий активный job. При отключённом
автообновлении или отсутствии задания предоставьте пользователю ручной запуск
`POST /forecast-jobs` с новым `Idempotency-Key`.

В `main` сейчас нет публичного `GET /tax-calendar`: он предложен в отдельном
PR #39. Добавляйте его в клиентский API только после слияния PR и включения
соответствующего маршрута в Gateway.
