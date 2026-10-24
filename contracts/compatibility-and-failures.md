# Совместимость контрактов, сбои и проверки

## Общие правила

- Версии существующих DTO/HTTP не выводить из содержимого этого каталога — они определяются реальным кодом, [Auth OpenAPI](../docs/openapi/auth-profile.yaml), [Gateway application.yml](../services/gateway-server/src/main/resources/application.yml), [handoff](../backend-skills/docs/ORCHESTRATOR_FORECAST_HANDOFF.md) и тестами.
- **Source of truth:** в случае расхождения сначала проверить текущие контроллеры, конфигурацию маршрутов, сериализаторы и тесты. Документация с пометкой draft не доказывает поддержку метода.
- Wire даты: `YYYY-MM-DD`; timestamp — UTC Z; деньги — decimal string scale 2, на стороне сервиса BigDecimal; UUID — canonical; `null` и `[]` не опускать там, где контракт требует присутствия поля.
- Клиент не подставляет внешние `X-User-Id`/`X-Auth-Session-Id`; service identity обеспечивается **сетевой изоляцией**, внутренние URL и финансовые тела не выводятся в клиент/логи.
- Обновление полей DTO проверять consumer-driven HTTP/fixture тестами обоих концов. Breaking change — согласовать с изменением публичных API в [Zapas](https://github.com/Digital-Hustle/zapas).

## Новый sync upload Preprocessor → Orchestrator

В `main` после PR #21 есть серверный клиент `HttpOrchestratorImportGateway`: stage с `Idempotency-Key` и query-метаданными, finalize с `If-Match` и recovery через GET при ошибке. Успешная новая фиксация → `201 COMMITTED`, найденная уже завершённая → `200 COMMITTED`. Structured Problem сохраняет `errors`, `traceId`, `retryAfterSec`, `resourceId`. Не отменять импорт после неоднозначного finalize. **Нерешённые ограничения:** новый endpoint не опубликован Gateway, compose не задаёт `PREPROCESSOR_ORCHESTRATOR_URL`, Orchestrator пока не применяет `ATOMIC`/`completeness` и хеш idempotency включает меняющийся `preparedAt`, отчего повтор может вернуть 409. Подробнее: [preprocessor-orchestrator.md](preprocessor-orchestrator.md).

## Сводка ошибок и повторов

| Граница | Ошибка / условие | Действие |
| --- | --- | --- |
| Gateway → Auth | 401 JWT/refresh; 403 CSRF/Origin; 429 rate limit; 503 SMTP | Повторять только разрешённые операции; уважать Retry-After; не auto-retry refresh без подтверждения |
| Preprocessor → Orchestrator (sync upload) | 400/401/413/415/422 local validation, upstream problem, 503 dependency; ambiguity on finalize | Reconcile `GET /imports/{id}`, no auto-cancel; повтор того же key пока может дать 409 из-за preparedAt |
| Gateway → Preprocessor | 400 missing file; 413 size/rows; 415 format; 422 broken CSV | Исправить CSV/запрос; `200 PARTIAL/INVALID` не значит успешный импорт |
| Gateway → Orchestrator | 401 context; 403 VIEWER; 404 чужой resource; 409 `If-Match` или idempotency; 428 нет `If-Match`; 422 invalid data | Не retry мутирующие операции без Idempotency-Key/доказанной безопасности |
| Orchestrator → Forecast | 400/404/409/422 | Terminal FAILED; не retry нечитаемые snapshot/данные |
| Orchestrator → Forecast | timeout/network/429/503/5xx | Retry с backoff и исходными IDs/байтами; соблюдать `Retry-After` |
| Orchestrator → Forecast | 200, но неверная арифметика/identity/horizon | `FAILED INVALID_FORECAST_RESULT`, ничего не сохранять |
| Worker | lease истёк до ответа | Не сохранять устаревший ответ; fencing по owner+attempt+expiry |

Форма ошибок обычно `application/problem+json` с `code,traceId,errors[]` и HTTP status; отличия Auth API и Forecast детально в соответствующих документах. Не предполагать один унифицированный enum всех ошибок между сервисами.

## Матрица минимальных контрактных проверок

| Стык | Минимальная проверка |
| --- | --- |
| Gateway ↔ Auth | Правильное сохранение route, cookies и отдельных Set-Cookie, stripping недоверенных headers, RS256, CSRF binding, сессия |
| Gateway ↔ Preprocessor | Multipart streaming, SetPath, неверный CSV, лимиты, JSON report и заголовок request ID |
| Preprocessor sync upload → Orchestrator | Stub tests: stage/finalize/recovery, count mapping, delegated X-User-Id, Problem preservation; **missing live Gateway route + real E2E** |
| Preprocessor JSON → Orchestrator importer | Свежий JSON одной компании, совпадение external company ID, повторный stage/finalize, malformed amount, UNKNOWN gaps. `PREPROCESSOR_SAMPLE_JSON` test |
| Gateway ↔ Orchestrator | Разрешённые exact path+method, 401/403/404 tenant roles, If-Match/Idempotency-Key, чтение jobs/results; внутренние пути недоступны |
| Orchestrator ↔ Forecast | Контракт `ForecastSnapshot`, fingerprints, 30-дневная арифметика и version identity, risk null при недостатке origins, backtest/replay, detection stability, retry/fencing |
| End-to-end | Auth → подготовка CSV → импорт → finalize → forecast job/polling → `forecasts/{id}`. Настоящие HTTP/DB сервисы обязательны для доказательства интеграции |

Команды из корня:

```bash
bash gradlew :services:auth-profile:test :services:auth-profile:build
bash gradlew :services:gateway-server:test :services:gateway-server:build
bash gradlew :services:preprocessor:test :services:preprocessor:build
bash gradlew :services:orchestrator:test :services:orchestrator:build
bash gradlew :services:forecast:test :services:forecast:build
```

Внутри Orchestrator: `PREPROCESSOR_SAMPLE_JSON=<path>` для реального JSON contract test. В Forecast: `python3 scripts/fingerprint.py src/test/resources/fixtures/calculate.json` (см. README). Ни локальные сборки, ни CI сами по себе не доказывают production network isolation, SMTP или сквозной pipeline.

## Новые/неподключённые функции

На текущем `main` **нет Gateway route** для `POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/recurring-detection-jobs`, хотя Forecast имеет внутренний `/recurring-detection`. После реализации добавить публичный маршрут, job DTO, новый negative test и пример сюда. Прямой HTTP-вызов `Preprocessor → Orchestrator` уже действует **для нового синхронного upload**, но публичный Gateway route пока отсутствует; для старого `prepare` push не выполняется. Брокер и дополнительные сервисы не входят в текущую архитектуру.
