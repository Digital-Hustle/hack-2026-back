# Preprocessor → Orchestrator: синхронный импорт CSV (после PR #21)

**Статус:** backend endpoint и прямой внутренний HTTP-клиент **вошли в main**. Однако путь **не включён в маршруты Gateway main**, а в `compose.full.yaml` для контейнера Preprocessor пока **не задан `PREPROCESSOR_ORCHESTRATOR_URL=http://orchestrator:8083`**: значение по умолчанию `http://localhost:8083` внутри контейнера указывает не на Orchestrator. Это документирование реализованного backend-контракта, **не утверждение о готовом публичном end-to-end**.

Источники: [ImportController](../services/preprocessor/src/main/java/ru/cashflow/preprocessor/api/controller/ImportController.java), [ImportControllerImpl](../services/preprocessor/src/main/java/ru/cashflow/preprocessor/api/controller/impl/ImportControllerImpl.java), [HTTP-клиент](../services/preprocessor/src/main/java/ru/cashflow/preprocessor/integrations/orchestrator/HttpOrchestratorImportGateway.java), [README](../services/preprocessor/README.md), [HTTP integration tests](../services/preprocessor/src/test/java/ru/cashflow/preprocessor/integrations/ImportUploadHttpTest.java).

## 1. Композитный HTTP endpoint Preprocessor

```http
POST /api/v1/preprocessor/companies/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/imports
Content-Type: multipart/form-data; boundary=<generated>
X-User-Id: cccccccc-cccc-cccc-cccc-cccccccccccc
Idempotency-Key: dddddddd-dddd-dddd-dddd-dddddddddddd

file: @statement.csv
accountId: bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb
validationMode: SKIP_INVALID
completeness: CONFIRMED_COMPLETE
declaredFromDate: 2026-01-01
declaredToDate: 2026-01-31
```

Пример показывает поля multipart **схематически**, не самостоятельное raw multipart body; генератор границ должен быть согласован с Content-Type. Текущий Gateway не маршрутизирует этот путь: для доступа через браузер нужен **отдельный Gateway route с JwtValidation + Origin/CSRF**, который удаляет клиентский `X-User-Id` и выставляет проверенный. Выставлять Preprocessor-порт наружу ради обхода Gateway **нельзя**.

| Поле | Тип, обязательность | Правило |
| --- | --- | --- |
| `companyId` | UUID path, required | Внутренняя компания; проверка прав делегируется Orchestrator |
| `X-User-Id` | UUID header, required | **Ровно один**, канонический UUID от доверенного Gateway; иначе 401 `AUTH_REQUIRED` |
| `Idempotency-Key` | UUID header, required | Повторный upload должен адресовать ту же команду, но см. известный дефект ниже |
| `file` | CSV multipart file, required | `.csv`; максимум 20 MiB, 50 000 строк |
| `accountId` | UUID multipart field, required | Счёт проверяется Orchestrator на принадлежность компании |
| `validationMode` | enum, required | `ATOMIC` либо `SKIP_INVALID` |
| `completeness` | enum, required | `UNKNOWN` либо `CONFIRMED_COMPLETE` |
| `declaredFromDate`, `declaredToDate` | ISO date multipart fields | Обе обязательны и `from ≤ to` при `CONFIRMED_COMPLETE`; при `UNKNOWN` игнорируются |

Preprocessor парсит файл существующим `StatementPreparationService`, вычисляет SHA-256 **байтов файла**, готовит `PreparedStatementRs`. Здесь не передаётся отдельный `companies` CSV в метод upload (в отличие от legacy prepare).

## 2. Реальный вызов Preprocessor → Orchestrator

Клиент `HttpOrchestratorImportGateway` делает **три потенциальных HTTP-вызова**, всегда по приватной сети без пользовательского JWT/cookies, с проверенным и делегированным `X-User-Id`.

1. **Stage**:
   ```http
   POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/imports?fileName=statement.csv&fileSha256=<sha256>&validationMode=SKIP_INVALID&completeness=CONFIRMED_COMPLETE&declaredFromDate=2026-01-01&declaredToDate=2026-01-31
   X-User-Id: <trusted-uuid>
   Idempotency-Key: <upload-uuid>
   Accept: application/json
   Content-Type: application/json; charset=utf-8

   <полный PreparedStatementRs, включая preparedAt/operations/dailyBalances/report>
   ```
   Для `UNKNOWN` `declaredFromDate/declaredToDate` в query не передаются. Сейчас Orchestrator принимает тело PreparedStatementRs и возвращает свою DTO `ImportRs`. **Важный разрыв:** опции `ATOMIC` и `completeness` пока **не применяются** бизнес-логикой Orchestrator; query-передача ещё не означает выполнение семантики.
2. **Finalize** (только когда stage вернул `STAGED` с version):
   ```http
   POST /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/imports/{importId}/finalize
   X-User-Id: <trusted-uuid>
   If-Match: "<version-from-stage>"
   Accept: application/json
   ```
   Body не передаётся. Orchestrator должен атомарно завершить фиксацию, вернуть `FINALIZED`. Preprocessor переводит его в публичный `COMMITTED`; **201** при успешной финализации в данном запросе.
3. **Reconciliation после неоднозначного/ошибочного finalize**:
   ```http
   GET /api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/imports/{importId}
   X-User-Id: <trusted-uuid>
   Accept: application/json
   ```
   Если импорт **уже `FINALIZED`**, Preprocessor возвращает `200 COMMITTED`; иначе **сохраняет исходную ошибку**, добавляя неудачу reconciliation как suppressed exception. **Автоматического `cancel` нет**, чтобы не отменить успешно закоммиченную транзакцию.
4. Если stage уже вернул `FINALIZED` для идемпотентного повтора — **200 COMMITTED**, второго finalize нет. `CANCELLED`/неожиданные состояния — конфликт.

## 3. DTO на границе

**Orchestrator → Preprocessor** (текущая wire DTO `OrchestratorImportRs`):

```json
{
  "id": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  "accountId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "status": "FINALIZED",
  "preprocessorStatus": "PREPARED",
  "rowsStaged": 1,
  "rowsInserted": 1,
  "rowsAlreadyPresent": 0,
  "issueCount": 0,
  "inputFingerprint": "0000000000000000000000000000000000000000000000000000",
  "createdAt": "2026-10-24T09:00:00Z",
  "finalizedAt": "2026-10-24T09:00:01Z",
  "version": 1
}
```

Это **схематическое** синтетическое сообщение. Поля, которые Preprocessor **проверяет**: `id`, `accountId`, `version`, `createdAt`, неотрицательный `rowsStaged`, известный status `STAGED|FINALIZED|CANCELLED`; при `FINALIZED` обязательны `rowsInserted` и `rowsAlreadyPresent`. Неизвестные дополнительные поля игнорируются.

**Preprocessor → вызывающая сторона** (`ImportRs`, реальный JSON shape; значения синтетические):

```json
{
  "id": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  "companyId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "accountId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "status": "COMMITTED",
  "fileName": "statement.csv",
  "fileSha256": "<lowercase-sha256-hex>",
  "totalRows": 1,
  "acceptedRows": 1,
  "rejectedRows": 0,
  "duplicateRows": 0,
  "warningCount": 1,
  "fromDate": "2026-09-30",
  "toDate": "2026-09-30",
  "createdAt": "2026-10-24T09:00:00Z",
  "completedAt": "2026-10-24T09:00:01Z",
  "dataVersion": null
}
```

`COMMITTED` соответствует `FINALIZED`; `STAGING` = `STAGED`; `ABORTED` = `CANCELLED`. `acceptedRows` после finalize — **`rowsInserted`**, `duplicateRows` — повторы парсера **плюс** `rowsAlreadyPresent`, `warningCount` — только предупреждения парсера (не `issueCount` с ошибками), `dataVersion=null`, так как wire ответ Orchestrator не содержит этого значения. `fromDate/toDate` — min/max **принятых операций**, не `declaredFromDate`.

## 4. Ошибки, повторы и текущие несовместимости

- `400` — отсутствующее поле, неправильный UUID/enum/дата; `401 AUTH_REQUIRED` — нет ровно одного доверенного X-User-Id; `413 PAYLOAD_TOO_LARGE` — >20 MiB или 50 000 строк; `415` — media type; `422` — неверный CSV или обязательные даты для `CONFIRMED_COMPLETE`; `503 DEPENDENCY_UNAVAILABLE` — сеть/таймаут/ошибка интеграции.
- При HTTP Problem от Orchestrator используется **реальный HTTP status**, а не поле `status` JSON. Сохраняются `code`, `detail`, `errors[]`, `traceId`, `retryAfterSec`, `resourceId` (если они были в структурированном теле).
- Ключи и retry: прямой клиент не следует redirects, connect timeout по умолчанию `3s`, response timeout `120s`. После ответа 503/409 при finalize выполняется **GET reconciliation**, не слепой новый finalize.
- **Известная несовместимость**: хеш идемпотентности Orchestrator в `main` пока учитывает меняющееся `preparedAt` из Prepared JSON. Поэтому повторная загрузка с тем же `Idempotency-Key` может привести к `409` **вместо ожидаемого 200**. Этот дефект нельзя скрывать в документации.
- В `compose.full.yaml` Preprocessor находится в сети `orchestrator`, **но `PREPROCESSOR_ORCHESTRATOR_URL` не установлен**; необходимо отдельно установить `http://orchestrator:8083`. Это **не** заменяет отсутствующий Gateway route.
- Реальный integration test использует HTTP stub Orchestrator; он проверяет stage/finalize, структурированные Problem, негативные поля и recovery. **Совместная работа с реальным Orchestrator, применением ATOMIC/completeness и доступом через Gateway остаётся отдельной проверкой.**

## 5. Старый prepare-путь не удалён

`POST /api/v1/preprocessor/statements/prepare` (через Gateway) → `/internal/v1/statements/prepare` всё ещё возвращает **Prepared JSON**, не persisted import. Он остаётся применим для отдельного ручного `POST Orchestrator /imports` + finalize. Старый и новый маршруты **разные**: не заменяйте их без изменения Gateway и клиента.
