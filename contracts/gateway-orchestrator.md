# Gateway → Orchestrator: публичный API и job lifecycle

Источники: [маршруты Gateway](../services/gateway-server/src/main/resources/application.yml), [Orchestrator README](../services/orchestrator/README.md). Все пути ниже — **публичные** с `/api/v1/orchestrator`, без StripPrefix/RewritePath, но Gateway **пускает только явно описанные сочетания Path + Method**.

Префикс компании `C=/api/v1/orchestrator/companies`; счёта `A=C/{companyId}/accounts/{accountId}`.

## Дополнительная совместимость с frontend (merged PR #23)

Gateway имеет route `orchestrator-query-scoped` с фильтрами `OrchestratorCompat` и `JwtValidation` перед основными маршрутами. Для `GET/POST/PUT/PATCH/DELETE` на `/api/v1/orchestrator/companies/{companyId}/payments[/**]` и `/imports[/**]` фильтр при корректном UUID `accountId` в query переписывает URL в `/companies/{companyId}/accounts/{accountId}/payments[/**]` или `/imports[/**]`, удаляет **только** `accountId` из query и сохраняет остальные параметры. При отсутствии/неверном UUID rewriting **не выполняется**; успешная обработка не гарантируется. Это legacy-адаптер маршрута, **не** разрешение обходить проверку CompanyScope в Orchestrator.

Например: `GET /api/v1/orchestrator/companies/{c}/payments?accountId={a}&limit=20` → `GET /api/v1/orchestrator/companies/{c}/accounts/{a}/payments?limit=20`. Не путать с новым upload Preprocessor, который Gateway **пока не публикует**.

## Реализованная поверхность через Gateway main

| Действие | Маршруты |
| --- | --- |
| Компании, членство, счета | `POST/GET C`, `GET/PUT C/{companyId}`, `GET/POST C/{companyId}/members`, `PATCH/DELETE C/{companyId}/members/{userId}`, `GET/POST C/{companyId}/accounts`, `GET/PUT C/{companyId}/accounts/{accountId}` |
| Факты/дневная история | `GET A/payments`, `GET A/payments/{id}/revisions`, `PUT A/payments/{id}/category`, `GET A/daily-history`, `POST A/completeness`, `POST A/balance-anchors` |
| Импорт | `POST/GET A/imports`, `GET A/imports/{id}`, `GET A/imports/{id}/issues`, `POST A/imports/{id}/finalize`, `POST A/imports/{id}/cancel` |
| Будущие платежи | `POST/GET A/expected-payments`, `GET/PUT A/expected-payments/{id}`, `POST A/expected-payments/{id}/cancel`, `POST/GET A/expected-payments/{id}/allocations`, `POST A/allocations/{id}/reverse` |
| Регулярности и настройки | `GET A/recurring-patterns`, `PUT A/recurring-patterns/{id}/status`, `GET/PUT A/forecast-settings` |
| Прогноз и backtest | `POST A/forecast-jobs`, `POST A/backtest-jobs`, `GET A/jobs`, `GET C/{companyId}/jobs/{id}`, `POST C/{companyId}/jobs/{id}/cancel`, `GET A/forecasts`, `GET A/forecasts/latest` (совпадает с `{id}` в Gateway predicate), `GET A/forecasts/{id}`, `GET A/backtests`, `GET A/backtests/{id}`, `GET A/backtests/{id}/origins`, `GET A/dashboard` |

**Нужна доработка:** `POST A/recurring-detection-jobs` **не включён** в текущий `application.yml` Gateway и полный публичный job-flow не подтверждён. Внутренний Forecast detector при этом есть. Не выдавать этот endpoint за публично доступный.

## Заголовки и права

- Доверенный `X-User-Id` устанавливает **только Gateway** после проверки RS256 access cookie; отсутствие, дубликат или неверный UUID → 401. Клиентский `X-User-Id` удаляется. Orchestrator использует `CompanyAccess`: нет компании/членства → 404, недостаточно роли → 403.
- OWNER/ACCOUNTANT: чтение и финансовые изменения. VIEWER: чтение. Только OWNER управляет участниками и метаданными компании.
- `Idempotency-Key` обязателен для `imports`, `forecast-jobs`, `backtest-jobs`; равный ключ+тело → то же задание/импорт, другое тело → 409.
- Изменения ресурса с optimistic locking требуют `If-Match: "<version>"` (например, счета, категории, планы, регулярности и импорт finalize): отсутствие → 428, несовпадение → 409. Отдельные POST-команды зависят от endpoint; сверяйте конкретный API.
- Mutating запрос через браузер также требует `Origin`, access/CSRF cookies и `X-CSRF-Token`; HTTPS обязателен для Secure cookies.

## Пример: запуск прогноза

```http
POST /api/v1/orchestrator/companies/22222222-2222-4222-8222-222222222222/accounts/33333333-3333-4333-8333-333333333333/forecast-jobs
Cookie: __Host-cashflow-access=<access-jwt>; __Host-cashflow-csrf=<csrf-cookie>
Origin: https://localhost
X-CSRF-Token: <csrf-token>
Idempotency-Key: 55555555-5555-4555-8555-555555555555
Content-Type: application/json

{"asOfDate":"2026-10-24","scope":"WITH_KNOWN_PLANS"}
```

При действительном cutoff, достаточной истории и доступе возвращается `202` с job (`QUEUED`); **не** мгновенный ForecastRs. `asOfDate` должен быть последним допустимым закрытым днём для current forecast. Worker позже вызывает Forecast `calculate`.

```http
GET /api/v1/orchestrator/companies/22222222-2222-4222-8222-222222222222/jobs/<jobId>
Cookie: __Host-cashflow-access=<access-jwt>
```

Job: `QUEUED → RUNNING → SUCCEEDED|FAILED` (+ `CANCELLED` для ожидающих). Используйте полученный `resultId` для `GET A/forecasts/{id}`. Отдельные `GET A/forecasts/latest`, `GET A/dashboard` отдают сохранённые данные. `staleness` вычисляется по текущему `companies.data_version`; поздний старый прогноз не заменяет более свежий.

## Пример backtest job

```http
POST /api/v1/orchestrator/companies/22222222-2222-4222-8222-222222222222/accounts/33333333-3333-4333-8333-333333333333/backtest-jobs
Idempotency-Key: 55555555-5555-4555-8555-555555555555
Content-Type: application/json
Cookie: __Host-cashflow-access=<access-jwt>; __Host-cashflow-csrf=<csrf-cookie>
Origin: https://localhost
X-CSRF-Token: <csrf-token>

{"asOfDate":"2026-10-01","firstOrigin":"2026-09-01","lastOrigin":"2026-09-01","stepDays":7,"scope":"HISTORY_ONLY","windowWeeks":12,"evaluateRisk":false}
```

`202` → polling job → `GET A/backtests/{resultId}` или `GET A/backtests/{id}/origins`. Исторические категории/планы/оплаты берутся только из знаний на каждом cutoff; неполные origins SKIPPED, не фальсифицируются.

## Важные ошибки

`401` — контекст/JWT, `403` — роль/CSRF, `404` — чужой/отсутствующий tenant/resource, `409` — конфликт версии/idempotency, `422` — некорректные финансовые данные/нет оцениваемых origins, `428` — нет If-Match. Формат — `application/problem+json` с `code`, `traceId`, `errors[]`, без утечек данных.
