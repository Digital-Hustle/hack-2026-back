# Orchestrator → Forecast: внутренний вычислительный HTTP API

**Единственный разрешённый caller — Orchestrator по закрытой сети.** Forecast не имеет JWT, пользователей, PostgreSQL, брокера или публичных методов чтения результатов. Источники: [handoff §4–6](../backend-skills/docs/ORCHESTRATOR_FORECAST_HANDOFF.md), [Forecast README](../services/forecast/README.md), [настоящие JSON fixtures](../services/forecast/src/test/resources/fixtures/), [HTTP примеры](../services/forecast/http/forecast.http). При расхождении handoff/draft и реализации — сверять валидатор/тесты.

## Заголовки, вызов, ошибки

| HTTP | Internal path | Body | Response |
| --- | --- | --- | --- |
| POST | `/internal/v1/forecast/calculate` | `ForecastSnapshot` | `200 ForecastRs` |
| POST | `/internal/v1/forecast/backtest` | `BacktestSnapshot` | `200 BacktestRs` |
| POST | `/internal/v1/forecast/recurring-detection` | `RecurringDetectionInput` | `200 {detectorVersion,patterns[]}` |

Каждый POST: `Content-Type: application/json`, `Idempotency-Key: <job-uuid>`, `X-Job-Id: <тот же job-uuid>`, `X-Calculation-Id: <result-uuid>`. **Для detection** CalculationId = jobId; для calculate/backtest — фиксированный `jobs.result_id`. Запрещены `Authorization`, cookies и `X-User-*`. Redirects не следовать. Response `application/problem+json`: malformed JSON 400, bad input/invariants 422, конфликт замены 409, превышение лимита 413, overload 503 + `Retry-After`. Не сериализовать rejectedValues/финансовое тело в ошибках и логах.

## Calculate

```http
POST http://127.0.0.1:8080/internal/v1/forecast/calculate
Content-Type: application/json
Idempotency-Key: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
X-Job-Id: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
X-Calculation-Id: aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa

< JSON из services/forecast/src/test/resources/fixtures/calculate.json >
```

**Исполняемый синтетический payload**, включая все поля и реальный fingerprint — [fixture calculate.json](../services/forecast/src/test/resources/fixtures/calculate.json). **Не** подставлять только обрезанный пример: пустой train может вызвать `INSUFFICIENT_HISTORY`, фальшивый fingerprint отклоняется. В `ForecastSnapshot` обязательны:
- `snapshotId`, `companyId`, `accountId`, `asOfDate`, `inputDataVersion`, `algorithmVersion` (v1 `WEEKDAY_MEAN_V1`);
- `settings`: `windowWeeks=8|10|12`, `horizonDays=30`, `bufferAmount`, `scenarioCount`, `minCalibrationOrigins`, `calibrationStepDays=7`, `algorithm`;
- `scope=HISTORY_ONLY|WITH_KNOWN_PLANS`, `startBalance` (уже B(T)), `startBalanceDate`, `historyFrom`;
- `dailyHistory[]` (без дыр в днях; OBSERVED/ZERO_CONFIRMED/MISSING), `payments[]` (уже категоризованы), `knownPlans[]`, `recurringPatterns[]` (только CONFIRMED), `completeRanges[]`, `calibrationOrigins[]`, `fingerprint`.
- Деньги — строки в копейках (`"10000.00"`), моменты — UTC Z, явный `null`, обязательные массивы `[]` (не отсутствующее поле).

В ответе `ForecastRs`: идентификаторы входа и job, `days[]` ровно T+1…T+30 с `plannedIn/Out`, `recurringIn/Out`, `residualIn/Out`, `inflow/outflow`, `balance`, P10/P50/P90 и вероятностями (если рассчитаны); summary/quality/risk и эпизоды `gaps[]`. Orchestrator сверяет ID, версию и арифметику `balance[d]=balance[d−1]+inflow−outflow` и составные потоки, затем **атомарно** сохраняет результаты. Пример из фикстуры ожидает первый разрыв 2026-10-29, min `-50000.00`, а риск `null` при отсутствии calibration.

`HISTORY_ONLY` требует `knownPlans=[]`; для `WITH_KNOWN_PLANS` включать лишь открытые/частично оплаченные планы с остатком >0. `REPLACES_RECURRING` и `REPLACES_BASELINE` заменяют конкретный прогнозный источник, не удваивают расходы. Overdue без решения пользователя **не** переносить автоматически.

### Fingerprint и калибровка

Canonical JSON SHA-256: сортировать поля/ключи и коллекции по handoff §5, `snapshotId` и `fingerprint` исключить на каждом уровне ForecastSnapshot; ISO UTC, `null` и `[]` сохраняются. Контракт проверяется с обеих сторон; `inputDataVersion` и бизнес-ID в хеше сохраняются.

`calibrationOrigins[]` — nested snapshots + известные позже `actualDays/actualPayments`, но только в пределах внешнего cutoff; `input.calibrationOrigins=[]`. Для `HISTORICAL_TRAJECTORY_BOOTSTRAP` нужны ≥12 зрелых origins (на сетке 7 дней); иначе `probability=null`, `scenarioCount=0`, а не фиктивный 0%. Ошибки и будущие факты не используются для обучения собственного origin. `probabilityCalibrated=false` в текущем v1.

## Backtest

```http
POST http://127.0.0.1:8080/internal/v1/forecast/backtest
Content-Type: application/json
Idempotency-Key: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
X-Job-Id: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
X-Calculation-Id: aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa

< JSON из services/forecast/src/test/resources/fixtures/backtest.json >
```

Полная fixture: [backtest.json](../services/forecast/src/test/resources/fixtures/backtest.json). Тело `BacktestSnapshot`: `snapshotId,companyId,accountId,inputDataVersion,request,settings,originSnapshots[],actualDays[]`; `request={accountId,firstOrigin,lastOrigin,stepDays:7..30,scope,windowWeeks,evaluateRisk}`. Origins — сетка `firstOrigin + k*stepDays ≤ lastOrigin`; max 100. Никакого современного состояния планов на прошлом cutoff. `BacktestRs` включает status COMPLETE/PARTIAL, `origins[]` с EVALUATED/SKIPPED и метрики MAE/RMSE, horizons 1…30. Пустой набор оценённых origins → 422. Orchestrator валидирует результат и хранит его у себя.

## Recurring detection

```http
POST http://127.0.0.1:8080/internal/v1/forecast/recurring-detection
Content-Type: application/json
Idempotency-Key: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
X-Job-Id: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
X-Calculation-Id: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb

{"accountId":"33333333-3333-4333-8333-333333333333","asOfDate":"2026-10-24","inputDataVersion":42,"payments":[]}
```

Это **структурный** пример; для семантического прогона использовать полную [fixture recurring-detection.json](../services/forecast/src/test/resources/fixtures/recurring-detection.json). Внутри `payments[]`: `paymentId,date,amount,direction,categoryCode,counterpartyKey`. Возвращает `{"detectorVersion":"<version>","patterns":[...]}` с кандидатами статуса DETECTED (могут быть пустыми). Паттерн включает accountId, direction, categoryCode, counterpartyKey, `schedule={frequency,weekday,dayOfMonth,businessDayRule}`, amount, observations и версию. Orchestrator merge по stable key и **не перезаписывает** CONFIRMED/EXCLUDED.

**Внимание:** текущий Gateway `main` не маршрутизирует публичный `POST .../recurring-detection-jobs`. Внутренний метод Forecast ≠ завершённая пользовательская фича.

## Worker, lease и retry

Orchestrator создаёт snapshot + `QUEUED` job в REPEATABLE READ транзакции, **после commit** worker делает HTTP вызов. Claim через PostgreSQL `SKIP LOCKED`, lease owner+attempt+expiry fencing; успешный 200 проходит валидацию и транзакционное сохранение только если lease ещё действителен. Сеть/timeout/429/503/5xx — retry с backoff и **теми же** bytes/job/calculation IDs; 400/404/409/422 и невалидный 200 — terminal FAILED. Поздний ответ после lease отбрасывается. Настройки клиента: `ORCHESTRATOR_FORECAST_URL`, `..._CONNECT_TIMEOUT`, `..._REQUEST_TIMEOUT`. Не держать DB transaction на время HTTP.
