# Orchestrator

Владелец бизнес-данных и своей PostgreSQL. Реализуется поэтапно по
`backend-skills/docs/ORCHESTRATOR_FORECAST_HANDOFF.md` (раздел 7): одна задача — один PR.

Java 21, Spring Boot 3.5.7, Spring JDBC (`JdbcClient`), Liquibase, PostgreSQL. Отдельная Gradle-сборка
в `services/orchestrator`, корневая сборка и соседние сервисы не затрагиваются.

## Запуск

```powershell
.\gradlew.bat clean check bootJar
$env:ORCHESTRATOR_DB_URL = "jdbc:postgresql://localhost:5432/orchestrator"
$env:ORCHESTRATOR_DB_USER = "orchestrator"
$env:ORCHESTRATOR_DB_PASSWORD = "<из секрета>"
java -jar build\libs\orchestrator.jar
```

По умолчанию слушает `127.0.0.1:8083`. Тесты поднимают embedded PostgreSQL (Zonky), Docker не нужен;
`TEST_DATABASE_URL` позволяет указать внешнюю БД. Как и у PreProcessor, на Windows путь с кириллицей
может ломать classpath тестов — используйте checkout с латинским путём.

## Доверенный контекст

JWT проверяет только Gateway. Orchestrator читает ровно один заголовок `X-User-Id` (UUID). Нет заголовка,
два заголовка или не-UUID → `401`. Эта схема безопасна **только** если порт Orchestrator недоступен
никому, кроме Gateway; сетевую изоляцию обеспечивает деплой, она здесь не проверяется.

`CompanyAccess` — единственное место, где из `userId + companyId` получается `CompanyScope`
(ArchitectureTest это закрепляет). Нет членства или нет компании → `404` (чужая компания не отличается
от несуществующей). Роль недостаточна → `403`.

| Действие | OWNER | ACCOUNTANT | VIEWER |
|---|:---:|:---:|:---:|
| Читать компанию, счета, участников | ✅ | ✅ | ✅ |
| Создавать/менять счета (и далее — финансовые данные) | ✅ | ✅ | ❌ |
| Менять компанию, управлять участниками | ✅ | ❌ | ❌ |

## Задача 1: компании, участники, счета, CompanyScope

- `companies` с `data_version` (версия финансовых входов, handoff §3) и `version` (optimistic locking).
- Создание компании и OWNER-membership в одной транзакции; партиальный unique-индекс гарантирует одного
  OWNER. Второй OWNER, передача владения и удаление OWNER отклоняются.
- `accounts`: только RUB, unique `(id, company_id)` под будущие составные FK, `external_company_id`
  уникален внутри компании (связь с `company_id` из CSV PreProcessor) — **но не даёт доступа** и не выбирает
  компанию сам.
- Изменения требуют `If-Match: "<version>"`: без заголовка `428`, устаревшая версия `409`.

Эндпоинты (`/api/v1/orchestrator/companies`): `POST /`, `GET /`, `GET|PUT /{companyId}`,
`GET|POST /{companyId}/members`, `PATCH|DELETE /{companyId}/members/{userId}`,
`GET|POST /{companyId}/accounts`, `GET|PUT /{companyId}/accounts/{accountId}`.

## Задача 2: платежи, дневная история, полнота, балансные якоря

- `payments` — неизменяемые по дате/сумме/направлению факты, `amount > 0`, составной FK
  `(account_id, company_id)`, partial unique `(company, account, external_operation_id)`.
- `payment_revisions` — append-only история категорий, включая исходную (revision 0). Правка категории
  пользователем (`PUT .../payments/{id}/category`, `If-Match`) добавляет revision `USER` и поднимает
  `dataVersion`; факт не переписывается.
- `completeness_ranges` и `balance_anchors` — append-only с `known_at`. Поздний диапазон перекрывает ранний;
  для даты побеждает якорь, известный последним. Ручной якорь не меняет выписку. Полным можно отметить
  только закрытый прошедший день (по часовому поясу компании).
- `daily_observations` — пересчитываемая проекция, а не источник истины. `DailyHistoryCalculator` строит её
  из фактов/якорей/полноты, **уже отфильтрованных по моменту знания**, поэтому тот же код используется и для
  исторических срезов (задачи 6, 11): прошлый cutoff не читает сегодняшнюю проекцию.
- Ровно один день на дату. `OBSERVED` — полный день с операциями, `ZERO_CONFIRMED` — подтверждённый
  полный день без операций (потоки `0.00`), `MISSING` — потоки и EOD `null`, хотя `paymentCount` отражает
  известные строки. EOD не суммируется. `balanceMismatch` — EOD не следует из предыдущего EOD и потоков.
- `startBalance(T)` — последний якорь ≤ T, протянутый только через полные дни; неизвестный день между ними →
  баланса нет (не протягиваем через пропуск, §2).
- `CounterpartyKeys` (версия `CPK1`) — стабильный ключ: ИНН важнее имени, имя нормализуется. Без ИНН и имени →
  `null`, связь не выдумывается.

Эндпоинты (`/api/v1/orchestrator/companies/{c}/accounts/{a}`): `GET /payments?from&to&limit&offset`,
`GET /payments/{id}/revisions`, `PUT /payments/{id}/category`, `POST /completeness`, `POST /balance-anchors`,
`GET /daily-history?from&to`. Запись фактов появится в задаче 3 (импорт из PreProcessor).

## Задача 3: импорт из PreProcessor — staging и атомарный finalize

`POST /api/v1/orchestrator/companies/{c}/accounts/{a}/imports` принимает **JSON PreProcessor без изменений**
(`schemaVersion: "1"`) и `Idempotency-Key`. Поток:

```text
PreProcessor JSON ──► stage (imports, staged_payments, staged_balances, import_issues) ──► finalize ──► payments
                      ничего не видно истории и прогнозу                                    одна транзакция
```

- Orchestrator **не доверяет** PreProcessor: суммы снова проверяются (`-?\d+\.\d{2}`, `amount > 0`), направление
  `IN|OUT`, ИНН, код категории, дубликаты `operationId`, только закрытые дни (по часовому поясу компании).
- `company_id` из CSV обязан совпадать с `externalCompanyId` счёта, иначе `422`. Он не выбирает компанию и не
  даёт доступа: права проверяет `CompanyAccess` до разбора (чужой → 404, VIEWER → 403).
- Тот же `Idempotency-Key` и то же тело → тот же импорт; другое тело → `409`. Хеш считается по канонически
  сериализованному телу, а не по сырым байтам.
- `finalize` (`If-Match`) в одной транзакции: блокирует импорт и компанию, вставляет платежи с
  `on conflict (company, account, external_operation_id) do nothing`, сохраняет исходную категорию как
  revision 0, якоря `STATEMENT` из `dailyBalances`, полноту периода выписки, поднимает `dataVersion` и
  пересчитывает проекцию. Повторный finalize возвращает тот же результат; повторный импорт той же выписки
  даёт `rowsInserted = 0`. Факт никогда не создаётся дважды.
- Интервалы длиннее 7 дней без операций (их же PreProcessor помечает `HISTORY_GAP`) записываются как `UNKNOWN`
  и становятся `MISSING`, а не нулевыми днями.
- `cancel` переводит STAGED в CANCELLED; завершённый импорт отменить нельзя.
- Категория PreProcessor: `RULE` → `category_source=RULE` с `rule_version`; `UNCLASSIFIED` → `OTHER`.
  Процент уверенности не выдумывается.

Эндпоинты: `POST /imports`, `POST /imports/{id}/finalize`, `POST /imports/{id}/cancel`, `GET /imports`,
`GET /imports/{id}`, `GET /imports/{id}/issues`.

Сквозная проверка с реальным выходом PreProcessor: `PREPROCESSOR_SAMPLE_JSON=<путь к JSON одной компании>`
включает `PreprocessorContractTest`; без переменной тест пропускается, данные организаторов в git не кладутся.

Ошибки — `application/problem+json` с `code`, `traceId`, `errors[]` (путь поля, без значений).
Логи: метод, статус, длительность и requestId; тела, query string, user id и суммы не пишутся.
