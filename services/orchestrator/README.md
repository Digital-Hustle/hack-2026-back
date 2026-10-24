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

Ошибки — `application/problem+json` с `code`, `traceId`, `errors[]` (путь поля, без значений).
Логи: метод, статус, длительность и requestId; тела, query string, user id и суммы не пишутся.
